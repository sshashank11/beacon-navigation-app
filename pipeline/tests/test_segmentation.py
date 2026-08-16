from __future__ import annotations

import json
import tempfile
import unittest
from io import BytesIO
from pathlib import Path

import httpx
import numpy as np
from PIL import Image

from beacon_pipeline.vision.segmentation import (
    ImageReference,
    download_image,
    render_segmentation_previews,
)


class SegmentationPreviewTest(unittest.TestCase):
    def test_renders_twenty_side_by_side_previews_and_class_manifest(self) -> None:
        samples = [
            (
                ImageReference(str(index), f"https://images.example/{index}.jpg"),
                Image.new("RGB", (8, 6), color=(index, 100, 150)),
            )
            for index in range(20)
        ]
        masks = [
            np.vstack(
                (
                    np.full((3, 8), 0, dtype=np.int64),
                    np.full((3, 8), 1, dtype=np.int64),
                )
            )
            for _ in samples
        ]

        with tempfile.TemporaryDirectory() as directory:
            result = render_segmentation_previews(
                samples,
                masks,
                {0: "road", 1: "vegetation"},
                Path(directory),
            )

            self.assertEqual(result.sample_count, 20)
            self.assertEqual(result.detected_classes, ("road", "vegetation"))
            self.assertEqual(len(list(Path(directory).glob("*.png"))), 20)
            with Image.open(Path(directory) / "0.png") as preview:
                self.assertEqual(preview.size, (16, 6))
            manifest = json.loads(result.manifest_path.read_text(encoding="utf-8"))
            self.assertEqual(manifest["sample_count"], 20)
            self.assertEqual(
                manifest["images"][0]["class_fractions"],
                {"road": 0.5, "vegetation": 0.5},
            )

    def test_rejects_a_mask_with_the_wrong_dimensions(self) -> None:
        sample = (
            ImageReference("sample", "https://images.example/sample.jpg"),
            Image.new("RGB", (8, 6)),
        )

        with (
            tempfile.TemporaryDirectory() as directory,
            self.assertRaisesRegex(ValueError, "does not match"),
        ):
            render_segmentation_previews(
                [sample],
                [np.zeros((6, 7), dtype=np.int64)],
                {0: "road"},
                Path(directory),
            )

    def test_thumbnail_download_retries_a_rate_limit(self) -> None:
        image_bytes = BytesIO()
        Image.new("RGB", (4, 3), color=(10, 20, 30)).save(
            image_bytes,
            format="PNG",
        )
        attempts = 0

        def handle(request: httpx.Request) -> httpx.Response:
            nonlocal attempts
            attempts += 1
            if attempts == 1:
                return httpx.Response(429, headers={"Retry-After": "0"})
            return httpx.Response(200, content=image_bytes.getvalue())

        with httpx.Client(transport=httpx.MockTransport(handle)) as client:
            image = download_image(client, "https://images.example/sample.png")

        self.assertEqual(attempts, 2)
        self.assertEqual(image.size, (4, 3))


if __name__ == "__main__":
    unittest.main()
