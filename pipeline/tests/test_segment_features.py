from __future__ import annotations

import unittest
from datetime import datetime, timedelta, timezone
from unittest import mock

from beacon_pipeline.vision import segment_features as segment_features_module
from beacon_pipeline.vision.segment_features import (
    TAU_YEARS_CROWD,
    TAU_YEARS_SKY_VIEW,
    ScoredFrame,
    aggregate_segment_features,
    crowd_density,
    recency_weight,
    weighted_median,
)


NOW = datetime(2026, 8, 16, tzinfo=timezone.utc)


def years_ago(years: float) -> datetime:
    return NOW - timedelta(days=365.25 * years)


def frame(
    segment_id: int,
    age_years: float,
    sky_view_factor: float = 0.5,
    crowd: float = 0.1,
    ego: float = 0.0,
) -> ScoredFrame:
    return ScoredFrame(
        segment_id=segment_id,
        captured_at=years_ago(age_years),
        sky_view_factor=sky_view_factor,
        crowd_density=crowd,
        ego_vehicle_frac=ego,
    )


class RecencyWeightTest(unittest.TestCase):
    def test_a_fresh_frame_weighs_one_and_decays_with_age(self) -> None:
        self.assertAlmostEqual(
            recency_weight(NOW, NOW, TAU_YEARS_SKY_VIEW), 1.0, places=6
        )
        one_tau = recency_weight(years_ago(5.0), NOW, TAU_YEARS_SKY_VIEW)
        self.assertAlmostEqual(one_tau, 0.367879, places=5)

    def test_crowd_decays_far_faster_than_canopy(self) -> None:
        captured_at = years_ago(2.0)

        self.assertLess(
            recency_weight(captured_at, NOW, TAU_YEARS_CROWD),
            recency_weight(captured_at, NOW, TAU_YEARS_SKY_VIEW),
        )

    def test_a_future_timestamp_does_not_exceed_one(self) -> None:
        self.assertEqual(
            recency_weight(NOW + timedelta(days=30), NOW, TAU_YEARS_CROWD), 1.0
        )

    def test_rejects_a_non_positive_tau(self) -> None:
        with self.assertRaisesRegex(ValueError, "tau_years must be positive"):
            recency_weight(NOW, NOW, 0.0)


class WeightedMedianTest(unittest.TestCase):
    def test_equal_weights_give_the_plain_median(self) -> None:
        self.assertEqual(weighted_median([0.1, 0.9, 0.5], [1.0, 1.0, 1.0]), 0.5)

    def test_a_dominant_weight_wins(self) -> None:
        self.assertEqual(weighted_median([0.1, 0.9], [99.0, 1.0]), 0.1)

    def test_zero_weights_fall_back_to_an_unweighted_median(self) -> None:
        self.assertEqual(weighted_median([0.2, 0.4, 0.6], [0.0, 0.0, 0.0]), 0.4)

    def test_rejects_mismatched_or_empty_input(self) -> None:
        with self.assertRaisesRegex(ValueError, "same length"):
            weighted_median([0.1], [1.0, 2.0])
        with self.assertRaisesRegex(ValueError, "must not be empty"):
            weighted_median([], [])


class CrowdDensityTest(unittest.TestCase):
    def test_sums_vehicle_and_person_fractions(self) -> None:
        histogram = {"car": 0.1, "bus": 0.05, "person": 0.02, "road": 0.6}

        self.assertAlmostEqual(crowd_density(histogram), 0.17, places=6)

    def test_ignores_non_crowd_classes_and_handles_empty(self) -> None:
        self.assertEqual(crowd_density({"road": 0.9, "sky": 0.1}), 0.0)
        self.assertEqual(crowd_density({}), 0.0)
        self.assertEqual(crowd_density(None), 0.0)


class DaylightFilterTest(unittest.TestCase):
    """Night frames must not reach aggregation; they read as false canyons."""

    def _captured_sql(self, **kwargs: object) -> tuple[str, tuple]:
        cursor = mock.MagicMock()
        cursor.fetchall.return_value = []
        cursor.__enter__ = mock.Mock(return_value=cursor)
        cursor.__exit__ = mock.Mock(return_value=False)
        connection = mock.MagicMock()
        connection.cursor.return_value = cursor
        connection.__enter__ = mock.Mock(return_value=connection)
        connection.__exit__ = mock.Mock(return_value=False)

        with mock.patch.object(
            segment_features_module.psycopg, "connect", return_value=connection
        ):
            segment_features_module.load_scored_frames("postgresql://unused", **kwargs)
        return cursor.execute.call_args[0][0], cursor.execute.call_args[0][1]

    def test_daylight_window_is_passed_by_default(self) -> None:
        sql, params = self._captured_sql()

        self.assertIn("AT TIME ZONE", sql)
        self.assertIn(True, params)
        self.assertIn(segment_features_module.IMAGE_TIMEZONE, params)
        self.assertIn(segment_features_module.DAYLIGHT_START_HOUR, params)
        self.assertIn(segment_features_module.DAYLIGHT_END_HOUR, params)

    def test_the_filter_can_be_turned_off(self) -> None:
        _, params = self._captured_sql(daylight_only=False)

        self.assertIn(False, params)

    def test_the_window_covers_daytime_only(self) -> None:
        self.assertLess(
            segment_features_module.DAYLIGHT_START_HOUR,
            segment_features_module.DAYLIGHT_END_HOUR,
        )
        self.assertGreaterEqual(segment_features_module.DAYLIGHT_START_HOUR, 6)
        self.assertLessEqual(segment_features_module.DAYLIGHT_END_HOUR, 19)


class AggregateSegmentFeaturesTest(unittest.TestCase):
    def test_one_sample_per_segment_with_frame_counts(self) -> None:
        frames = [
            frame(1, 0.1),
            frame(1, 0.2),
            frame(2, 0.1),
        ]

        samples = aggregate_segment_features(frames, NOW)

        self.assertEqual([sample.segment_id for sample in samples], [1, 2])
        self.assertEqual(samples[0].frame_count, 2)
        self.assertEqual(samples[1].frame_count, 1)

    def test_a_stale_frame_loses_the_crowd_vote_to_a_fresh_one(self) -> None:
        # Same segment, opposite crowd readings, five years apart.
        frames = [
            frame(1, 5.0, crowd=0.9),
            frame(1, 0.0, crowd=0.1),
        ]

        sample = aggregate_segment_features(frames, NOW)[0]

        self.assertEqual(sample.crowd_density, 0.1)

    def test_the_two_taus_read_the_same_frames_differently(self) -> None:
        # Two 2-year-old frames agree with each other; one fresh frame
        # disagrees. At tau = 5y the older pair still outweighs the fresh
        # frame, so canopy keeps their reading. At tau = 0.5y the same pair
        # has decayed away and the fresh frame decides the crowd reading.
        frames = [
            frame(1, 2.0, sky_view_factor=0.8, crowd=0.8),
            frame(1, 2.0, sky_view_factor=0.9, crowd=0.9),
            frame(1, 0.0, sky_view_factor=0.2, crowd=0.2),
        ]

        sample = aggregate_segment_features(frames, NOW)[0]

        self.assertEqual(sample.sky_view_factor, 0.8)
        self.assertEqual(sample.crowd_density, 0.2)

    def test_dashcam_frames_do_not_inflate_the_crowd_prior(self) -> None:
        frames = [
            frame(1, 0.0, crowd=0.9, ego=0.95),
            frame(1, 0.0, crowd=0.1, ego=0.0),
        ]

        sample = aggregate_segment_features(frames, NOW)[0]

        self.assertEqual(sample.crowd_density, 0.1)

    def test_a_dashcam_frame_still_reports_its_sky(self) -> None:
        # A dashboard blocks the road, not the sky, so canopy keeps the frame.
        frames = [frame(1, 0.0, sky_view_factor=0.42, crowd=0.9, ego=0.95)]

        sample = aggregate_segment_features(frames, NOW)[0]

        self.assertEqual(sample.sky_view_factor, 0.42)

    def test_a_segment_seen_only_by_dashcam_still_gets_a_crowd_value(self) -> None:
        # Excluding every frame would leave a hole; fall back rather than drop.
        frames = [frame(1, 0.0, crowd=0.7, ego=0.99)]

        sample = aggregate_segment_features(frames, NOW)[0]

        self.assertEqual(sample.crowd_density, 0.7)

    def test_no_frames_produce_no_samples(self) -> None:
        self.assertEqual(aggregate_segment_features([], NOW), [])


if __name__ == "__main__":
    unittest.main()
