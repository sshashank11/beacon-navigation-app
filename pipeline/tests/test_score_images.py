from __future__ import annotations

import unittest
from collections.abc import Sequence
from io import BytesIO
from unittest import mock

import httpx
import numpy as np
from PIL import Image

from beacon_pipeline.vision import score_images as score_images_module
from beacon_pipeline.vision.score_images import (
    PERSON_CLASSES,
    VEHICLE_CLASSES,
    FrameMetrics,
    count_instances,
    derive_frame_metrics,
    score_frames,
    sky_view_factor,
)
from beacon_pipeline.vision.segmentation import ImageReference


LABELS = {0: "road", 1: "sky", 2: "vegetation", 3: "sidewalk", 4: "car", 5: "person"}


class FakeSegmenter:
    model_id = "fake"
    device = "test"
    labels = LABELS

    def __init__(self, masks: Sequence[np.ndarray]) -> None:
        self._masks = list(masks)
        self.batch_sizes: list[int] = []
        self._offset = 0

    def predict(self, images: Sequence[Image.Image]) -> list[np.ndarray]:
        self.batch_sizes.append(len(images))
        batch = self._masks[self._offset : self._offset + len(images)]
        self._offset += len(images)
        return batch


class SkyViewFactorTest(unittest.TestCase):
    def test_all_sky_is_one_and_no_sky_is_zero(self) -> None:
        height, width = 10, 4
        self.assertEqual(
            sky_view_factor(np.full((height, width), 1, dtype=np.int64), LABELS),
            1.0,
        )
        self.assertEqual(
            sky_view_factor(np.full((height, width), 0, dtype=np.int64), LABELS),
            0.0,
        )

    def test_sky_high_in_the_frame_outweighs_the_same_sky_low_down(self) -> None:
        height, width = 10, 4
        top = np.full((height, width), 0, dtype=np.int64)
        top[:3, :] = 1
        bottom = np.full((height, width), 0, dtype=np.int64)
        bottom[-3:, :] = 1

        top_svf = sky_view_factor(top, LABELS)
        bottom_svf = sky_view_factor(bottom, LABELS)

        self.assertGreater(top_svf, bottom_svf)
        # Same sky pixel fraction (0.3) in both frames.
        self.assertGreater(top_svf, 0.3)
        self.assertLess(bottom_svf, 0.3)

    def test_a_canyon_scores_below_an_open_street(self) -> None:
        height, width = 20, 10
        canyon = np.full((height, width), 0, dtype=np.int64)
        canyon[:2, 4:6] = 1
        open_street = np.full((height, width), 0, dtype=np.int64)
        open_street[:8, :] = 1

        self.assertLess(
            sky_view_factor(canyon, LABELS),
            sky_view_factor(open_street, LABELS),
        )

    def test_missing_sky_label_scores_zero(self) -> None:
        mask = np.full((6, 6), 1, dtype=np.int64)

        self.assertEqual(sky_view_factor(mask, {0: "road"}), 0.0)


class InstanceCountTest(unittest.TestCase):
    def test_counts_separated_blobs_and_ignores_speckle(self) -> None:
        mask = np.zeros((40, 40), dtype=np.int64)
        mask[2:12, 2:12] = 4
        mask[2:12, 20:30] = 4
        mask[38, 38] = 4  # single-pixel speckle

        self.assertEqual(count_instances(mask, LABELS, VEHICLE_CLASSES), 2)

    def test_touching_objects_collapse_into_one_component(self) -> None:
        mask = np.zeros((40, 40), dtype=np.int64)
        mask[2:12, 2:30] = 4

        self.assertEqual(count_instances(mask, LABELS, VEHICLE_CLASSES), 1)

    def test_absent_classes_count_zero(self) -> None:
        mask = np.zeros((40, 40), dtype=np.int64)

        self.assertEqual(count_instances(mask, LABELS, VEHICLE_CLASSES), 0)
        self.assertEqual(count_instances(mask, LABELS, PERSON_CLASSES), 0)


class DeriveFrameMetricsTest(unittest.TestCase):
    def test_reports_fractions_counts_and_no_construction(self) -> None:
        mask = np.zeros((40, 40), dtype=np.int64)
        mask[:10, :] = 1  # sky across the top quarter
        mask[10:20, :] = 2  # vegetation
        mask[30:, :20] = 3  # sidewalk
        mask[20:28, 2:12] = 4  # one vehicle
        mask[20:28, 20:30] = 5  # one person

        metrics = derive_frame_metrics("img-1", mask, LABELS)

        self.assertEqual(metrics.mapillary_id, "img-1")
        self.assertEqual(metrics.sky_frac, 0.25)
        self.assertEqual(metrics.vegetation_frac, 0.25)
        self.assertEqual(metrics.vehicle_count, 1)
        self.assertEqual(metrics.person_count, 1)
        self.assertFalse(metrics.construction_present)
        self.assertEqual(metrics.construction_conf, 0.0)
        self.assertGreater(metrics.sky_view_factor, metrics.sky_frac)
        self.assertEqual(metrics.class_histogram["sky"], 0.25)


class ScoreFramesTest(unittest.TestCase):
    def _samples(self, count: int) -> list[tuple[ImageReference, Image.Image]]:
        return [
            (
                ImageReference(str(index), f"https://images.example/{index}.jpg"),
                Image.new("RGB", (8, 6)),
            )
            for index in range(count)
        ]

    def test_batches_at_the_configured_size(self) -> None:
        samples = self._samples(70)
        masks = [np.zeros((6, 8), dtype=np.int64) for _ in samples]
        segmenter = FakeSegmenter(masks)

        metrics = score_frames(samples, segmenter, batch_size=32)

        self.assertEqual(len(metrics), 70)
        self.assertEqual(segmenter.batch_sizes, [32, 32, 6])
        self.assertIsInstance(metrics[0], FrameMetrics)
        self.assertEqual(metrics[0].mapillary_id, "0")

    def test_rejects_a_mask_with_the_wrong_dimensions(self) -> None:
        samples = self._samples(1)
        segmenter = FakeSegmenter([np.zeros((6, 7), dtype=np.int64)])

        with self.assertRaisesRegex(ValueError, "does not match"):
            score_frames(samples, segmenter)

    def test_rejects_a_non_positive_batch_size(self) -> None:
        with self.assertRaisesRegex(ValueError, "batch_size must be positive"):
            score_frames(self._samples(1), FakeSegmenter([]), batch_size=0)


class StreamingScoreRunTest(unittest.TestCase):
    """score_unscored_images must never hold the whole corridor in memory."""

    def test_downloads_and_persists_one_batch_at_a_time(self) -> None:
        total = 10
        batch_size = 4
        references = [
            ImageReference(str(index), f"https://images.example/{index}.jpg")
            for index in range(total)
        ]
        live_downloads: list[int] = []
        persisted: list[int] = []

        def handle(request: httpx.Request) -> httpx.Response:
            live_downloads.append(len(live_downloads))
            buffer = BytesIO()
            Image.new("RGB", (8, 6)).save(buffer, format="PNG")
            return httpx.Response(200, content=buffer.getvalue())

        segmenter = FakeSegmenter([np.zeros((6, 8), dtype=np.int64)] * total)

        with (
            mock.patch.object(
                score_images_module, "load_unscored_images", return_value=references
            ),
            mock.patch.object(
                score_images_module, "count_unscored_images", return_value=0
            ),
            mock.patch.object(
                score_images_module,
                "persist_frame_metrics",
                side_effect=lambda url, metrics, version: (
                    persisted.append(len(metrics)) or len(metrics)
                ),
            ),
            httpx.Client(transport=httpx.MockTransport(handle)) as client,
        ):
            result = score_images_module.score_unscored_images(
                "postgresql://unused",
                batch_size=batch_size,
                segmenter=segmenter,
                client=client,
            )

        self.assertEqual(result.scored_count, total)
        # Persisted per batch rather than once at the end.
        self.assertEqual(persisted, [4, 4, 2])
        self.assertEqual(segmenter.batch_sizes, [4, 4, 2])

    def test_no_pending_images_does_no_work(self) -> None:
        with mock.patch.object(
            score_images_module, "load_unscored_images", return_value=[]
        ):
            result = score_images_module.score_unscored_images("postgresql://unused")

        self.assertEqual(result.scored_count, 0)
        self.assertEqual(result.model_id, "")


if __name__ == "__main__":
    unittest.main()
