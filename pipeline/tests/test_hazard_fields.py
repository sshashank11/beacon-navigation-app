from __future__ import annotations

import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import rasterio
from rasterio.transform import from_origin
from shapely import from_wkt

from beacon_pipeline.model.hazard_fields import HazardMean, _bands_from_raster


class HazardFieldModelTest(unittest.TestCase):
    def test_live_mean_shifts_baseline_raster_into_more_severe_bands(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            raster_path = Path(directory) / "pm25.tif"
            values = np.arange(1, 17, dtype="float32").reshape((4, 4))
            with rasterio.open(
                raster_path,
                "w",
                driver="GTiff",
                width=4,
                height=4,
                count=1,
                dtype="float32",
                crs="EPSG:4326",
                transform=from_origin(-74.1, 40.9, 0.01, 0.01),
            ) as dataset:
                dataset.write(values, 1)

            observed_at = datetime(2026, 8, 15, tzinfo=timezone.utc)
            clean = _bands_from_raster(
                raster_path,
                HazardMean("pm25", float(values.mean()), observed_at),
            )
            smoke = _bands_from_raster(
                raster_path,
                HazardMean("pm25", float(values.mean() * 5), observed_at),
            )

        self.assertEqual({band.severity for band in clean}, {1, 2, 3, 4})
        self.assertGreater(min(band.severity for band in smoke), 1)
        self.assertTrue(all(from_wkt(band.geometry_wkt).is_valid for band in clean))


if __name__ == "__main__":
    unittest.main()
