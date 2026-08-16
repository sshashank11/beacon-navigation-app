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

from collections.abc import Sequence
from dataclasses import dataclass, field

import numpy as np
from PIL import Image
from rasterio.features import shapes
from shapely.geometry import shape

from beacon_pipeline.vision.segmentation import (
    ImageReference,
    SemanticSegmenter,
    class_histogram,
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


def _class_ids(labels: dict[int, str], class_names: frozenset[str]) -> set[int]:
    return {
        int(class_id) for class_id, name in labels.items() if name in class_names
    }


def _frame_shape(mask: np.ndarray) -> tuple[int, int]:
    if mask.ndim != 2:
        raise ValueError("mask must be a two-dimensional label array")
    return int(mask.shape[0]), int(mask.shape[1])
