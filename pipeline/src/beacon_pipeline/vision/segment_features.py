"""Aggregate per-image CV metrics into per-segment static scores.

Mapillary frames can be years old, and the features age at very different
rates: street canyon geometry and canopy barely move, while the cars and
people in a frame are gone by the next morning. Each feature therefore gets
its own ``exp(-age_years / tau)`` recency weight before aggregation.

Aggregation is a weighted median rather than a weighted mean, so one badly
segmented frame cannot drag a segment. The weights decide which frame sits at
the middle of the distribution; they do not shrink the value itself, so a
segment covered only by old imagery still reports its measured value rather
than decaying toward zero.

``segment_static_score.sky_view_factor`` is a benefit like ``shade_benefit``:
a high percentile means open sky. A low percentile is the street canyon that
traps particulates.
"""

from __future__ import annotations

import math
from collections import defaultdict
from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from datetime import datetime, timezone

import psycopg

from beacon_pipeline.vision.score_images import (
    MODEL_VERSION,
    PERSON_CLASSES,
    VEHICLE_CLASSES,
)


DAYS_PER_YEAR = 365.25
SECONDS_PER_YEAR = DAYS_PER_YEAR * 24 * 60 * 60

# Canopy and canyon geometry persist; traffic and pedestrians do not.
TAU_YEARS_SKY_VIEW = 5.0
TAU_YEARS_CROWD = 0.5

CROWD_CLASSES = VEHICLE_CLASSES | PERSON_CLASSES

# Sky detection needs daylight. After dark the model reads the sky as unlit
# structure, which collapses the sky view factor toward zero and makes an open
# street look like a canyon. Measured on the NoMad corridor, night frames
# average a sky view factor of 0.006 against 0.086 for daylight frames on the
# same streets. Night frames are still scored and kept for the route filmstrip;
# they are only barred from driving routing scores.
IMAGE_TIMEZONE = "America/New_York"
DAYLIGHT_START_HOUR = 8
DAYLIGHT_END_HOUR = 17

# Above this share of the lower frame, the vehicle pixels are the camera car
# rather than traffic. Such frames still describe the sky and the canopy
# honestly, so they are dropped from the crowd prior only.
MAX_EGO_VEHICLE_FRAC = 0.5


@dataclass(frozen=True)
class ScoredFrame:
    segment_id: int
    captured_at: datetime
    sky_view_factor: float
    crowd_density: float
    ego_vehicle_frac: float = 0.0

    @property
    def crowd_is_usable(self) -> bool:
        """False when the frame is mostly the camera vehicle's own dashboard."""
        return self.ego_vehicle_frac <= MAX_EGO_VEHICLE_FRAC


@dataclass(frozen=True)
class SegmentImageSample:
    segment_id: int
    sky_view_factor: float
    crowd_density: float
    frame_count: int


@dataclass(frozen=True)
class SegmentFeatureResult:
    frame_count: int
    excluded_dark_count: int
    segment_count: int
    model_version: str


def refresh_image_segment_features(
    database_url: str,
    model_version: str = MODEL_VERSION,
    now: datetime | None = None,
    daylight_only: bool = True,
) -> SegmentFeatureResult:
    frames = load_scored_frames(database_url, model_version, daylight_only)
    samples = aggregate_segment_features(frames, now)
    _replace_segment_samples(database_url, samples, model_version)
    _refresh_image_percentiles(database_url)
    return SegmentFeatureResult(
        frame_count=len(frames),
        excluded_dark_count=(
            count_dark_frames(database_url, model_version) if daylight_only else 0
        ),
        segment_count=len(samples),
        model_version=model_version,
    )


def aggregate_segment_features(
    frames: Iterable[ScoredFrame],
    now: datetime | None = None,
) -> list[SegmentImageSample]:
    """Collapse scored frames into one recency-weighted sample per segment."""
    reference_time = now or datetime.now(timezone.utc)
    grouped: dict[int, list[ScoredFrame]] = defaultdict(list)
    for frame in frames:
        grouped[frame.segment_id].append(frame)

    samples: list[SegmentImageSample] = []
    for segment_id, segment_frames in sorted(grouped.items()):
        sky_weights = [
            recency_weight(frame.captured_at, reference_time, TAU_YEARS_SKY_VIEW)
            for frame in segment_frames
        ]
        # A dashboard says nothing about how busy the street is.
        crowd_frames = [
            frame for frame in segment_frames if frame.crowd_is_usable
        ] or segment_frames
        crowd_weights = [
            recency_weight(frame.captured_at, reference_time, TAU_YEARS_CROWD)
            for frame in crowd_frames
        ]
        samples.append(
            SegmentImageSample(
                segment_id=segment_id,
                sky_view_factor=weighted_median(
                    [frame.sky_view_factor for frame in segment_frames],
                    sky_weights,
                ),
                crowd_density=weighted_median(
                    [frame.crowd_density for frame in crowd_frames],
                    crowd_weights,
                ),
                frame_count=len(segment_frames),
            )
        )
    return samples


def recency_weight(
    captured_at: datetime,
    now: datetime,
    tau_years: float,
) -> float:
    if tau_years <= 0:
        raise ValueError("tau_years must be positive")

    age_years = max((now - captured_at).total_seconds(), 0.0) / SECONDS_PER_YEAR
    return math.exp(-age_years / tau_years)


def weighted_median(values: Sequence[float], weights: Sequence[float]) -> float:
    """Lower weighted median: the value where cumulative weight reaches half."""
    if len(values) != len(weights):
        raise ValueError("values and weights must be the same length")
    if not values:
        raise ValueError("values must not be empty")

    ordered = sorted(zip(values, weights, strict=True))
    total = math.fsum(weight for _, weight in ordered)
    if total <= 0.0:
        # Every frame decayed to nothing; fall back to an unweighted median.
        midpoint = len(ordered) // 2
        return float(ordered[midpoint][0])

    cumulative = 0.0
    for value, weight in ordered:
        cumulative += weight
        if cumulative >= total / 2.0:
            return float(value)
    return float(ordered[-1][0])


def load_scored_frames(
    database_url: str,
    model_version: str = MODEL_VERSION,
    daylight_only: bool = True,
) -> list[ScoredFrame]:
    with psycopg.connect(database_url) as connection, connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT
              image.nearest_segment_id,
              image.captured_at,
              analysis.sky_view_factor,
              analysis.raw_class_hist,
              analysis.ego_vehicle_frac
            FROM image_analysis analysis
            JOIN street_image image
              ON image.mapillary_id = analysis.mapillary_id
            WHERE analysis.model_version = %s
              AND image.nearest_segment_id IS NOT NULL
              AND (
                NOT %s
                OR extract(
                  hour from image.captured_at AT TIME ZONE %s
                ) BETWEEN %s AND %s
              )
            """,
            (
                model_version,
                daylight_only,
                IMAGE_TIMEZONE,
                DAYLIGHT_START_HOUR,
                DAYLIGHT_END_HOUR,
            ),
        )
        return [
            ScoredFrame(
                segment_id=int(row[0]),
                captured_at=row[1],
                sky_view_factor=float(row[2]),
                crowd_density=crowd_density(row[3]),
                ego_vehicle_frac=float(row[4] or 0.0),
            )
            for row in cursor.fetchall()
        ]


def count_dark_frames(
    database_url: str,
    model_version: str = MODEL_VERSION,
) -> int:
    """Scored frames held back from aggregation for being outside daylight."""
    with psycopg.connect(database_url) as connection, connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT count(*)
            FROM image_analysis analysis
            JOIN street_image image
              ON image.mapillary_id = analysis.mapillary_id
            WHERE analysis.model_version = %s
              AND image.nearest_segment_id IS NOT NULL
              AND extract(
                hour from image.captured_at AT TIME ZONE %s
              ) NOT BETWEEN %s AND %s
            """,
            (model_version, IMAGE_TIMEZONE, DAYLIGHT_START_HOUR, DAYLIGHT_END_HOUR),
        )
        row = cursor.fetchone()
        return int(row[0]) if row else 0


def crowd_density(class_histogram: dict[str, float] | None) -> float:
    """Pixel fraction covered by vehicles and people.

    Pixel fraction rather than instance count: segmentation cannot separate
    touching objects, and density is what the crowd prior actually needs.
    """
    if not class_histogram:
        return 0.0

    total = math.fsum(
        float(class_histogram.get(name, 0.0) or 0.0) for name in CROWD_CLASSES
    )
    return min(max(total, 0.0), 1.0)


def _replace_segment_samples(
    database_url: str,
    samples: Sequence[SegmentImageSample],
    model_version: str,
) -> None:
    with psycopg.connect(database_url) as connection, connection.cursor() as cursor:
        cursor.execute("DELETE FROM segment_image_sample")
        if samples:
            cursor.executemany(
                """
                INSERT INTO segment_image_sample
                  (segment_id, sky_view_factor_raw, crowd_density_raw,
                   frame_count, model_version, sampled_at)
                VALUES (%s, %s, %s, %s, %s, now())
                """,
                [
                    (
                        sample.segment_id,
                        sample.sky_view_factor,
                        sample.crowd_density,
                        sample.frame_count,
                        model_version,
                    )
                    for sample in samples
                ],
            )
        connection.commit()


def _refresh_image_percentiles(database_url: str) -> None:
    with psycopg.connect(database_url) as connection, connection.cursor() as cursor:
        cursor.execute(
            """
            WITH ranked AS (
              SELECT
                segment_id,
                PERCENT_RANK() OVER (ORDER BY sky_view_factor_raw) * 100 AS svf,
                PERCENT_RANK() OVER (ORDER BY crowd_density_raw) * 100 AS crowd
              FROM segment_image_sample
            )
            INSERT INTO segment_static_score
              (segment_id, sky_view_factor, crowd_prior, computed_at)
            SELECT segment_id, svf, crowd, now()
            FROM ranked
            ON CONFLICT (segment_id) DO UPDATE SET
              sky_view_factor = EXCLUDED.sky_view_factor,
              crowd_prior = EXCLUDED.crowd_prior,
              computed_at = EXCLUDED.computed_at
            """
        )
        connection.commit()
