from __future__ import annotations

import csv
import io
import math
import unittest

from beacon_pipeline.epa_facilities import (
    distance_decay,
    parse_echo_facilities,
    parse_tri_facilities,
)


class EpaFacilitiesTest(unittest.TestCase):
    def test_parses_and_deduplicates_tri_facilities(self) -> None:
        rows = csv.DictReader(
            io.StringIO(
                "3. FRS ID,4. FACILITY NAME,8. ST,12. LATITUDE,13. LONGITUDE\n"
                "1100001,Queens Works,NY,40.75,-73.90\n"
                "1100001,Queens Works,NY,40.75,-73.90\n"
                "1100002,Buffalo Works,NY,42.88,-78.87\n"
            )
        )

        facilities = list(parse_tri_facilities(rows))

        self.assertEqual(len(facilities), 1)
        self.assertEqual(facilities[0].source_id, "1100001")
        self.assertEqual(facilities[0].programs, ("TRI",))

    def test_keeps_echo_facilities_with_industrial_programs(self) -> None:
        rows = csv.DictReader(
            io.StringIO(
                "FacName,FacState,RegistryID,FacLat,FacLong,AIRIDs,NPDESIDs,RCRAIDs,RmpIDs,TRIIDs,EisIDs\n"
                "Brooklyn Plant,NY,2200001,40.68,-73.98,AIR-1,,,,,\n"
                "Unregulated Office,NY,2200002,40.70,-73.99,,,,,,\n"
            )
        )

        facilities = list(parse_echo_facilities(rows))

        self.assertEqual(len(facilities), 1)
        self.assertEqual(facilities[0].source_id, "2200001")
        self.assertEqual(facilities[0].programs, ("CAA",))

    def test_distance_decay_uses_300_meter_kernel(self) -> None:
        self.assertEqual(distance_decay(0), 1.0)
        self.assertAlmostEqual(distance_decay(300), math.exp(-1))
        self.assertAlmostEqual(distance_decay(1_000), math.exp(-10 / 3))
        with self.assertRaises(ValueError):
            distance_decay(-1)


if __name__ == "__main__":
    unittest.main()
