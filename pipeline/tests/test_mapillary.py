from __future__ import annotations

import unittest

import httpx

from beacon_pipeline.ingest.mapillary import (
    MAPILLARY_FIELDS,
    MAPILLARY_PAGE_SIZE,
    NOMAD_DEMO_CORRIDOR_BBOX,
    _fetch_tile,
    _parse_image,
    tile_bboxes,
)


class MapillaryIngestTest(unittest.TestCase):
    def test_demo_corridor_is_split_into_sub_point_zero_one_degree_tiles(self) -> None:
        queries = tile_bboxes(NOMAD_DEMO_CORRIDOR_BBOX)

        self.assertGreater(len(queries), 1)
        self.assertTrue(
            all(
                east - west < 0.01 and north - south < 0.01
                for west, south, east, north in queries
            )
        )

    def test_fetch_tile_requests_required_fields_and_follows_cursors(self) -> None:
        requests: list[httpx.Request] = []

        def respond(request: httpx.Request) -> httpx.Response:
            requests.append(request)
            if request.url.params.get("after") is None:
                return httpx.Response(
                    200,
                    json={
                        "data": [image_payload("first", 1_723_737_600_000)],
                        "paging": {"cursors": {"after": "next-page"}},
                    },
                )
            return httpx.Response(
                200,
                json={"data": [image_payload("second", "2026-08-15T16:00:00Z")]},
            )

        with httpx.Client(
            base_url="https://graph.mapillary.com",
            headers={"Authorization": "OAuth test-token"},
            transport=httpx.MockTransport(respond),
        ) as client:
            images = _fetch_tile(client, (-73.99, 40.74, -73.985, 40.745))

        self.assertEqual([image.mapillary_id for image in images], ["first", "second"])
        self.assertEqual(len(requests), 2)
        self.assertEqual(requests[0].url.params["fields"], MAPILLARY_FIELDS)
        self.assertEqual(int(requests[0].url.params["limit"]), MAPILLARY_PAGE_SIZE)
        self.assertEqual(requests[0].headers["Authorization"], "OAuth test-token")
        self.assertEqual(images[0].captured_at.isoformat(), "2024-08-15T16:00:00+00:00")

    def test_incomplete_image_is_not_harvested(self) -> None:
        payload = image_payload("missing-heading", "2026-08-15T16:00:00Z")
        del payload["compass_angle"]

        self.assertIsNone(_parse_image(payload))


def image_payload(mapillary_id: str, captured_at: object) -> dict[str, object]:
    return {
        "id": mapillary_id,
        "geometry": {"type": "Point", "coordinates": [-73.987, 40.742]},
        "compass_angle": 372.0,
        "captured_at": captured_at,
        "thumb_1024_url": f"https://images.example/{mapillary_id}.jpg",
    }


if __name__ == "__main__":
    unittest.main()
