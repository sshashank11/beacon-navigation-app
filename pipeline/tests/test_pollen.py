from __future__ import annotations

import unittest

from beacon_pipeline.ingest.pollen import _even_sample, _parse_forecast


class PollenIngestTest(unittest.TestCase):
    def test_forecast_maps_google_types_to_canonical_hazards(self) -> None:
        rows = _parse_forecast(
            {
                "dailyInfo": [
                    {
                        "date": {"year": 2026, "month": 8, "day": 15},
                        "pollenTypeInfo": [
                            {"code": "TREE", "indexInfo": {"value": 4}},
                            {"code": "GRASS"},
                            {"code": "WEED", "indexInfo": {"value": 2}},
                        ],
                    }
                ]
            },
            40.7,
            -74.0,
        )

        values = {row.hazard: row.value for row in rows}
        self.assertEqual(values, {"pollen_tree": 4.0, "pollen_grass": 0.0, "pollen_weed": 2.0})

    def test_even_sample_never_exceeds_daily_budget(self) -> None:
        points = [(float(index), float(index)) for index in range(100)]

        sampled = _even_sample(points, 60)

        self.assertEqual(len(sampled), 60)
        self.assertEqual(sampled[0], points[0])
        self.assertEqual(sampled[-1], points[-1])


if __name__ == "__main__":
    unittest.main()
