from __future__ import annotations

import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import rasterio
from rasterio.transform import from_origin
from shapely import from_wkt

from beacon_pipeline.model.hazard_fields import (
    MAX_HAZARD_FIELDS,
    HazardBand,
    HazardMean,
    _bands_from_raster,
    _prioritize_hazard_fields,
)


class HazardFieldModelTest(unittest.TestCase):
    def test_field_budget_keeps_the_most_severe_band_from_every_hazard(self) -> None:
        observed_at = datetime(2026, 8, 15, tzinfo=timezone.utc)
        candidates = [
            HazardBand(
                hazard=f"hazard_{hazard_index}",
                observed_at=observed_at,
                band_min=float((severity - 1) * 25),
                band_max=float(severity * 25),
                severity=severity,
                geometry_wkt="POLYGON EMPTY",
            )
            for hazard_index in range(6)
            for severity in range(1, 5)
        ]

        selected = _prioritize_hazard_fields(candidates)

        self.assertEqual(len(selected), MAX_HAZARD_FIELDS)
        self.assertEqual(
            {band.hazard for band in selected if band.severity == 4},
            {f"hazard_{index}" for index in range(6)},
        )
        self.assertEqual(
            [band.severity for band in selected],
            sorted((band.severity for band in selected), reverse=True),
        )

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
        clean_severe_area = sum(
            from_wkt(band.geometry_wkt).area
            for band in clean
            if band.severity == 4
        )
        smoke_severe_area = sum(
            from_wkt(band.geometry_wkt).area
            for band in smoke
            if band.severity == 4
        )
        self.assertGreater(smoke_severe_area, clean_severe_area)


if __name__ == "__main__":
    unittest.main()
