from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from beacon_pipeline.osm import (
    _expected_md5,
    _md5_matches,
    dissolve_borough_boundaries,
)


class OsmPreparationTest(unittest.TestCase):
    def test_dissolves_five_borough_features_into_one_feature(self) -> None:
        payload = {
            "type": "FeatureCollection",
            "features": [
                {
                    "type": "Feature",
                    "properties": {"boro": index},
                    "geometry": {
                        "type": "Polygon",
                        "coordinates": [[
                            [index, 0],
                            [index + 1, 0],
                            [index + 1, 1],
                            [index, 1],
                            [index, 0],
                        ]],
                    },
                }
                for index in range(5)
            ],
        }

        result = dissolve_borough_boundaries(payload)

        self.assertEqual(len(result["features"]), 1)
        self.assertEqual(result["features"][0]["geometry"]["type"], "Polygon")

    def test_requires_all_five_boroughs(self) -> None:
        with self.assertRaisesRegex(ValueError, "exactly five"):
            dissolve_borough_boundaries({"type": "FeatureCollection", "features": []})

    def test_validates_download_checksum(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sample.pbf"
            path.write_bytes(b"beacon")
            checksum = "41b89d10619cdd3e30fb6c401c17c7d9"

            self.assertEqual(_expected_md5(f"{checksum}  sample.pbf"), checksum)
            self.assertTrue(_md5_matches(path, checksum))


if __name__ == "__main__":
    unittest.main()
