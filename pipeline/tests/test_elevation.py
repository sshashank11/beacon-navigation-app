from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

import numpy as np
import rasterio
from rasterio.transform import from_origin

from beacon_pipeline.elevation import ElevationSampler, calculate_grade_pct


class ElevationTest(unittest.TestCase):
    def test_samples_raster_and_returns_none_outside_coverage(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "elevation.tif"
            with rasterio.open(
                path,
                "w",
                driver="GTiff",
                height=2,
                width=2,
                count=1,
                dtype="float32",
                crs="EPSG:4326",
                transform=from_origin(-74.0, 41.0, 0.01, 0.01),
                nodata=-9999.0,
            ) as raster:
                raster.write(np.array([[10.0, 20.0], [30.0, 40.0]], dtype="float32"), 1)

            with ElevationSampler([path]) as sampler:
                samples = sampler.sample([(-73.995, 40.995), (-75.0, 40.0)])

            self.assertEqual(samples[0], 10.0)
            self.assertIsNone(samples[1])

    def test_calculates_signed_grade_and_clamps_dem_noise(self) -> None:
        self.assertEqual(calculate_grade_pct(10.0, 15.0, 100.0), (5.0, False))
        self.assertEqual(calculate_grade_pct(20.0, 10.0, 100.0), (-10.0, False))
        self.assertEqual(calculate_grade_pct(0.0, 50.0, 100.0), (20.0, True))
        self.assertEqual(calculate_grade_pct(None, 10.0, 100.0), (None, False))


if __name__ == "__main__":
    unittest.main()
