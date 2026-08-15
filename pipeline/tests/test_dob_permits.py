from __future__ import annotations

import unittest

from beacon_pipeline.ingest.dob_permits import _permit_severity


class DobPermitIngestTest(unittest.TestCase):
    def test_permit_severity_prioritizes_disruptive_work(self) -> None:
        self.assertEqual(_permit_severity("Full Demolition"), 4)
        self.assertEqual(_permit_severity("General Construction"), 3)
        self.assertEqual(_permit_severity("Sidewalk Shed"), 2)
        self.assertEqual(_permit_severity("Plumbing"), 1)


if __name__ == "__main__":
    unittest.main()
