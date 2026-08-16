from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

import numpy as np
import rasterio
from rasterio.transform import from_origin

from beacon_pipeline.nyccas import (
    NyccasSampler,
    discover_latest_grids,
    mean_valid,
    reproject_raster,
    sample_positions,
)


class NyccasTest(unittest.TestCase):
    def test_discovers_latest_pollutant_grids(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            expected = {}
            for grid_name in (
                "aa15_pm300m",
                "aa16_pm300m",
                "aa16_no2300m",
                "s16_o3300m",
            ):
                header = root / grid_name / "hdr.adf"
                header.parent.mkdir()
                header.touch()
                if grid_name in {"aa16_pm300m", "aa16_no2300m", "s16_o3300m"}:
                    expected[grid_name] = header

            paths, years = discover_latest_grids(root)

            self.assertEqual(paths["pm25"], expected["aa16_pm300m"])
            self.assertEqual(paths["no2"], expected["aa16_no2300m"])
            self.assertEqual(paths["ozone"], expected["s16_o3300m"])
            self.assertEqual(years, {"pm25": 16, "no2": 16, "ozone": 16})

    def test_reprojects_state_plane_raster_before_sampling(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.tif"
            destination = root / "wgs84.tif"
            with rasterio.open(
                source,
                "w",
                driver="GTiff",
                height=4,
                width=4,
                count=1,
                dtype="float32",
                crs="EPSG:2263",
                transform=from_origin(980_000, 200_000, 300, 300),
                nodata=-9999.0,
            ) as raster:
                raster.write(np.full((4, 4), 12.5, dtype="float32"), 1)

            reproject_raster(source, destination)

            with rasterio.open(destination) as raster:
                self.assertEqual(raster.crs.to_epsg(), 4326)
                point = (
                    (raster.bounds.left + raster.bounds.right) / 2,
                    (raster.bounds.bottom + raster.bounds.top) / 2,
                )
            with NyccasSampler(
                {
                    "pm25": destination,
                    "no2": destination,
                    "ozone": destination,
                }
            ) as sampler:
                result = sampler.sample([[point]])

            self.assertAlmostEqual(result[0]["pm25"], 12.5)

    def test_uses_three_points_only_for_long_segments(self) -> None:
        self.assertEqual(sample_positions(74.99), (0.5,))
        self.assertEqual(sample_positions(75.0), (0.25, 0.5, 0.75))
        self.assertEqual(mean_valid([1.0, None, 3.0]), 2.0)


if __name__ == "__main__":
    unittest.main()
