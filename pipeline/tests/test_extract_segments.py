from __future__ import annotations

import json
import unittest

from shapely import from_wkt

from beacon_pipeline.extract_segments import (
    haversine_m,
    is_walk_or_bike_accessible,
    segment_rows,
    sidewalk_value,
    split_coordinates,
)


class SegmentExtractionTest(unittest.TestCase):
    def test_splits_long_way_without_losing_endpoints(self) -> None:
        coordinates = [[-73.99, 40.75], [-73.99, 40.753]]

        chunks = list(split_coordinates(coordinates, maximum_length_m=100.0))

        self.assertGreater(len(chunks), 3)
        self.assertEqual(chunks[0][0], tuple(coordinates[0]))
        self.assertEqual(chunks[-1][-1], tuple(coordinates[-1]))
        self.assertTrue(all(haversine_m(chunk[0], chunk[-1]) <= 100.01 for chunk in chunks))
        for previous, current in zip(chunks, chunks[1:], strict=False):
            self.assertEqual(previous[-1], current[0])

    def test_builds_segment_rows_from_osmium_geojson_sequence(self) -> None:
        record = "\x1e" + json.dumps({
            "type": "Feature",
            "id": "w42",
            "geometry": {
                "type": "LineString",
                "coordinates": [[-73.99, 40.75], [-73.99, 40.752]],
            },
            "properties": {"highway": "residential", "sidewalk": "both"},
        })

        rows = list(segment_rows([record]))

        self.assertGreater(len(rows), 1)
        self.assertEqual({row.osm_way_id for row in rows}, {42})
        self.assertEqual([row.seq for row in rows], list(range(len(rows))))
        self.assertTrue(all(row.has_sidewalk for row in rows))
        self.assertTrue(all(from_wkt(row.geometry_ewkt.removeprefix("SRID=4326;")).is_valid for row in rows))

    def test_filters_inaccessible_ways_and_detects_sidewalks(self) -> None:
        self.assertFalse(is_walk_or_bike_accessible({"highway": "motorway"}))
        self.assertFalse(is_walk_or_bike_accessible({"highway": "service", "access": "private"}))
        self.assertTrue(is_walk_or_bike_accessible({
            "highway": "service",
            "access": "private",
            "foot": "permissive",
        }))
        self.assertTrue(is_walk_or_bike_accessible({"highway": "steps", "bicycle": "no"}))
        self.assertTrue(sidewalk_value({"sidewalk:left": "yes"}))
        self.assertFalse(sidewalk_value({"sidewalk": "no"}))
        self.assertIsNone(sidewalk_value({"highway": "residential"}))


if __name__ == "__main__":
    unittest.main()
