"""Per-frame semantic metrics for harvested street imagery.

Instance counts come from connected-component labelling of the semantic mask.
Semantic segmentation assigns pixel classes, not object instances, so a row of
parked cars that touch in the frame collapses into a single component: these
counts undercount dense traffic and are a coarse instance proxy only. The pixel
fractions kept in ``class_histogram`` are the honest density signal, and they
are what feeds the segment-level crowd prior downstream.

Cityscapes has no construction class, so ``construction_present`` is always
false here and DOB permit data remains the construction source of truth.
Inferring construction from unrelated classes such as ``fence`` or ``wall``
would be a fabricated signal.
"""

from __future__ import annotations

import json
from collections.abc import Callable, Sequence
from dataclasses import dataclass, field

import httpx
import numpy as np
import psycopg
from PIL import Image
from rasterio.features import shapes
from shapely.geometry import shape

from beacon_pipeline.vision.segmentation import (
    ImageReference,
    SegformerSegmenter,
    SemanticSegmenter,
    class_histogram,
    download_image,
)


MODEL_VERSION = "segformer-b0-cityscapes-1024-v1"
DEFAULT_BATCH_SIZE = 32

SKY_CLASS = "sky"
VEGETATION_CLASS = "vegetation"
ROAD_CLASS = "road"
SIDEWALK_CLASS = "sidewalk"
VEHICLE_CLASSES = frozenset({"car", "truck", "bus", "motorcycle"})
PERSON_CLASSES = frozenset({"person", "rider"})

# Blobs below both thresholds are segmentation speckle rather than objects.
MIN_INSTANCE_AREA_FRACTION = 0.0005
MIN_INSTANCE_PIXELS = 16

# Cityscapes cannot support construction detection. See the module docstring.
CONSTRUCTION_SUPPORTED = False


@dataclass(frozen=True)
class ScoreImagesResult:
    scored_count: int
    pending_count: int
    model_version: str
    model_id: str
    device: str
    skipped: tuple[tuple[str, str], ...] = ()


@dataclass(frozen=True)
class FrameMetrics:
    mapillary_id: str
    vegetation_frac: float
    sky_frac: float
    road_frac: float
    sidewalk_frac: float
    sky_view_factor: float
    vehicle_count: int
    person_count: int
    construction_present: bool = False
    construction_conf: float = 0.0
    class_histogram: dict[str, float] = field(default_factory=dict)


def score_frames(
    samples: Sequence[tuple[ImageReference, Image.Image]],
    segmenter: SemanticSegmenter,
    batch_size: int = DEFAULT_BATCH_SIZE,
) -> list[FrameMetrics]:
    """Segment every sample in batches and derive its per-frame metrics."""
    if batch_size <= 0:
        raise ValueError("batch_size must be positive")

    metrics: list[FrameMetrics] = []
    for offset in range(0, len(samples), batch_size):
        batch = samples[offset : offset + batch_size]
        masks = segmenter.predict([image for _, image in batch])
        if len(masks) != len(batch):
            raise ValueError("segmenter returned a mask count that does not match")
        for (reference, image), raw_mask in zip(batch, masks, strict=True):
            mask = np.asarray(raw_mask)
            if mask.ndim != 2 or mask.shape != (image.height, image.width):
                raise ValueError(
                    f"mask for {reference.mapillary_id} does not match its "
                    "image dimensions"
                )
            metrics.append(
                derive_frame_metrics(reference.mapillary_id, mask, segmenter.labels)
            )
    return metrics


def derive_frame_metrics(
    mapillary_id: str,
    mask: np.ndarray,
    labels: dict[int, str],
) -> FrameMetrics:
    histogram = class_histogram(mask, labels)
    return FrameMetrics(
        mapillary_id=mapillary_id,
        vegetation_frac=histogram.get(VEGETATION_CLASS, 0.0),
        sky_frac=histogram.get(SKY_CLASS, 0.0),
        road_frac=histogram.get(ROAD_CLASS, 0.0),
        sidewalk_frac=histogram.get(SIDEWALK_CLASS, 0.0),
        sky_view_factor=sky_view_factor(mask, labels),
        vehicle_count=count_instances(mask, labels, VEHICLE_CLASSES),
        person_count=count_instances(mask, labels, PERSON_CLASSES),
        class_histogram=histogram,
    )


def sky_view_factor(mask: np.ndarray, labels: dict[int, str]) -> float:
    """Sky fraction weighted by vertical position; upper rows count for more.

    A low value means the frame is walled in by buildings, which is the street
    canyon geometry that traps particulates near the sidewalk. Weighting by row
    keeps a strip of sky at the horizon from reading like open sky overhead.
    """
    height, width = _frame_shape(mask)
    if height == 0 or width == 0:
        return 0.0

    sky_ids = _class_ids(labels, {SKY_CLASS})
    if not sky_ids:
        return 0.0

    weights = np.linspace(1.0, 0.0, height, dtype=np.float64)
    total_weight = float(weights.sum()) * width
    if total_weight <= 0.0:
        return 0.0

    sky_per_row = np.isin(mask, list(sky_ids)).sum(axis=1)
    weighted_sky = float(np.dot(weights, sky_per_row))
    return round(min(max(weighted_sky / total_weight, 0.0), 1.0), 6)


def count_instances(
    mask: np.ndarray,
    labels: dict[int, str],
    class_names: frozenset[str],
) -> int:
    """Count connected components of the given classes, ignoring speckle."""
    height, width = _frame_shape(mask)
    if height == 0 or width == 0:
        return 0

    target_ids = _class_ids(labels, class_names)
    if not target_ids:
        return 0

    binary = np.isin(mask, list(target_ids))
    if not binary.any():
        return 0

    minimum_area = max(
        float(MIN_INSTANCE_PIXELS),
        binary.size * MIN_INSTANCE_AREA_FRACTION,
    )
    source = binary.astype(np.uint8)
    return sum(
        1
        for geometry, value in shapes(source, mask=binary, connectivity=8)
        if value == 1 and shape(geometry).area >= minimum_area
    )


def score_unscored_images(
    database_url: str,
    model_version: str = MODEL_VERSION,
    limit: int | None = None,
    batch_size: int = DEFAULT_BATCH_SIZE,
    device: str | None = None,
    segmenter: SemanticSegmenter | None = None,
    client: httpx.Client | None = None,
    progress: Callable[[int, int], None] | None = None,
) -> ScoreImagesResult:
    """Score every snapped image not yet analysed under ``model_version``.

    Images already scored under this version are skipped, so the job is safe to
    re-run and only pays for inference once per checkpoint.

    Work streams one batch at a time: a corridor harvest is tens of thousands
    of frames, and holding them all as decoded bitmaps would exhaust memory.
    Each batch is persisted before the next is fetched, so an interrupted run
    keeps everything it finished and resumes where it stopped.
    """
    references = load_unscored_images(database_url, model_version, limit)
    if not references:
        return ScoreImagesResult(0, 0, model_version, "", "")

    active_segmenter = segmenter or SegformerSegmenter.load(device=device)
    owns_client = client is None
    image_client = client or httpx.Client(
        timeout=30.0,
        follow_redirects=True,
        headers={"User-Agent": "BeaconNavigationApp/0.1"},
    )
    scored = 0
    skipped: list[tuple[str, str]] = []
    try:
        for offset in range(0, len(references), batch_size):
            chunk = references[offset : offset + batch_size]
            samples = []
            for reference in chunk:
                try:
                    samples.append(
                        (reference, download_image(image_client, reference.thumb_url))
                    )
                except (httpx.HTTPError, OSError) as exception:
                    # One unreachable thumbnail must not end the run. Without
                    # this, a permanently dead URL would abort at the same
                    # image on every restart and the job could never finish.
                    skipped.append((reference.mapillary_id, str(exception)))
            if not samples:
                continue
            metrics = score_frames(samples, active_segmenter, batch_size=batch_size)
            scored += persist_frame_metrics(database_url, metrics, model_version)
            if progress is not None:
                progress(scored, len(references))
    finally:
        if owns_client:
            image_client.close()

    return ScoreImagesResult(
        scored_count=scored,
        pending_count=count_unscored_images(database_url, model_version),
        model_version=model_version,
        model_id=active_segmenter.model_id,
        device=active_segmenter.device,
        skipped=tuple(skipped),
    )


def load_unscored_images(
    database_url: str,
    model_version: str = MODEL_VERSION,
    limit: int | None = None,
) -> list[ImageReference]:
    with psycopg.connect(database_url) as connection, connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT image.mapillary_id, image.thumb_url
            FROM street_image image
            WHERE image.nearest_segment_id IS NOT NULL
              AND NOT EXISTS (
                SELECT 1
                FROM image_analysis analysis
                WHERE analysis.mapillary_id = image.mapillary_id
                  AND analysis.model_version = %s
              )
            ORDER BY image.captured_at DESC, image.mapillary_id
            LIMIT %s
            """,
            (model_version, limit),
        )
        return [ImageReference(str(row[0]), str(row[1])) for row in cursor.fetchall()]


def count_unscored_images(
    database_url: str,
    model_version: str = MODEL_VERSION,
) -> int:
    with psycopg.connect(database_url) as connection, connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT count(*)
            FROM street_image image
            WHERE image.nearest_segment_id IS NOT NULL
              AND NOT EXISTS (
                SELECT 1
                FROM image_analysis analysis
                WHERE analysis.mapillary_id = image.mapillary_id
                  AND analysis.model_version = %s
              )
            """,
            (model_version,),
        )
        row = cursor.fetchone()
        return int(row[0]) if row else 0


def persist_frame_metrics(
    database_url: str,
    metrics: Sequence[FrameMetrics],
    model_version: str = MODEL_VERSION,
) -> int:
    rows = list(metrics)
    if not rows:
        return 0

    with psycopg.connect(database_url) as connection, connection.cursor() as cursor:
        cursor.executemany(
            """
            INSERT INTO image_analysis
              (mapillary_id, model_version, vegetation_frac, sky_frac, road_frac,
               sidewalk_frac, sky_view_factor, vehicle_count, person_count,
               construction_present, construction_conf, raw_class_hist,
               analyzed_at)
            VALUES
              (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s::jsonb, now())
            ON CONFLICT (mapillary_id, model_version) DO UPDATE SET
              vegetation_frac = EXCLUDED.vegetation_frac,
              sky_frac = EXCLUDED.sky_frac,
              road_frac = EXCLUDED.road_frac,
              sidewalk_frac = EXCLUDED.sidewalk_frac,
              sky_view_factor = EXCLUDED.sky_view_factor,
              vehicle_count = EXCLUDED.vehicle_count,
              person_count = EXCLUDED.person_count,
              construction_present = EXCLUDED.construction_present,
              construction_conf = EXCLUDED.construction_conf,
              raw_class_hist = EXCLUDED.raw_class_hist,
              analyzed_at = EXCLUDED.analyzed_at
            """,
            [
                (
                    row.mapillary_id,
                    model_version,
                    row.vegetation_frac,
                    row.sky_frac,
                    row.road_frac,
                    row.sidewalk_frac,
                    row.sky_view_factor,
                    row.vehicle_count,
                    row.person_count,
                    row.construction_present,
                    row.construction_conf,
                    json.dumps(row.class_histogram, sort_keys=True),
                )
                for row in rows
            ],
        )
        connection.commit()
    return len(rows)


def _class_ids(labels: dict[int, str], class_names: frozenset[str]) -> set[int]:
    return {
        int(class_id) for class_id, name in labels.items() if name in class_names
    }


def _frame_shape(mask: np.ndarray) -> tuple[int, int]:
    if mask.ndim != 2:
        raise ValueError("mask must be a two-dimensional label array")
    return int(mask.shape[0]), int(mask.shape[1])
