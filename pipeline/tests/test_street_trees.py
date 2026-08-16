from __future__ import annotations

import unittest

import httpx

from beacon_pipeline.street_trees import (
    fetch_street_tree_batches,
    parse_street_tree,
)


class StreetTreesTest(unittest.TestCase):
    def test_parses_living_tree_and_extracts_genus(self) -> None:
        tree = parse_street_tree(
            {
                "tree_id": "42",
                "spc_latin": "Acer rubrum",
                "spc_common": "red maple",
                "status": "Alive",
                "tree_dbh": "12",
                "latitude": "40.7128",
                "longitude": "-74.0060",
            }
        )

        self.assertIsNotNone(tree)
        assert tree is not None
        self.assertEqual(tree.genus, "Acer")
        self.assertEqual(tree.dbh_inches, 12.0)

    def test_rejects_dead_or_out_of_bounds_trees(self) -> None:
        self.assertIsNone(parse_street_tree({"status": "Dead"}))
        self.assertIsNone(
            parse_street_tree(
                {
                    "tree_id": "1",
                    "status": "Alive",
                    "latitude": "42.0",
                    "longitude": "-74.0",
                }
            )
        )

    def test_uses_tree_id_keyset_pagination(self) -> None:
        requests: list[httpx.Request] = []

        def handle(request: httpx.Request) -> httpx.Response:
            requests.append(request)
            last_tree_id = 0 if "tree_id > 0" in request.url.params["$where"] else 4
            ids = [3, 4] if last_tree_id == 0 else [7]
            return httpx.Response(
                200,
                json=[
                    {
                        "tree_id": str(tree_id),
                        "spc_latin": "Quercus palustris",
                        "spc_common": "pin oak",
                        "status": "Alive",
                        "tree_dbh": "10",
                        "latitude": "40.7128",
                        "longitude": "-74.0060",
                    }
                    for tree_id in ids
                ],
            )

        with httpx.Client(transport=httpx.MockTransport(handle)) as client:
            batches = list(fetch_street_tree_batches(client, page_size=2))

        self.assertEqual(
            [[tree.tree_id for tree in batch] for batch in batches], [[3, 4], [7]]
        )
        self.assertIn("tree_id > 4", requests[1].url.params["$where"])


if __name__ == "__main__":
    unittest.main()
