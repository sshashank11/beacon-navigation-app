from __future__ import annotations

import json
import math
import unittest

from beacon_pipeline.traffic import traffic_decay_score, traffic_road_rows


class TrafficTest(unittest.TestCase):
    def test_extracts_weighted_road_classes(self) -> None:
        records = [
            _feature(11, "motorway"),
            _feature(12, "primary_link"),
            _feature(13, "residential"),
        ]

        roads = list(traffic_road_rows(records))

        self.assertEqual([road.osm_way_id for road in roads], [11, 12])
        self.assertEqual([road.highway_class for road in roads], ["motorway", "primary"])
        self.assertEqual([road.proxy_weight for road in roads], [5.0, 3.0])

    def test_combines_class_weight_and_distance_decay(self) -> None:
        score = traffic_decay_score([(5.0, 0.0), (3.0, 300.0)])

        self.assertAlmostEqual(score, 5.0 + 3.0 * math.exp(-1))
        with self.assertRaises(ValueError):
            traffic_decay_score([(2.0, -1.0)])


def _feature(way_id: int, highway_class: str) -> str:
    return json.dumps(
        {
            "id": f"w{way_id}",
            "geometry": {
                "type": "LineString",
                "coordinates": [[-74.0, 40.7], [-73.99, 40.71]],
            },
            "properties": {"highway": highway_class},
        }
    )


if __name__ == "__main__":
    unittest.main()
