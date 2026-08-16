from __future__ import annotations

import math
from datetime import datetime, timezone
from typing import Any

import httpx
import mercantile

from beacon_pipeline.config import Settings
from beacon_pipeline.db import StreetImage, upsert_street_images


MAPILLARY_GRAPH_URL = "https://graph.mapillary.com"
MAPILLARY_FIELDS = "id,geometry,compass_angle,captured_at,thumb_1024_url"
MAPILLARY_PAGE_SIZE = 2_000
MAPILLARY_TILE_ZOOM = 16
MAX_QUERY_SPAN_DEGREES = 0.01
NOMAD_DEMO_CORRIDOR_BBOX = (-73.9945, 40.7350, -73.9825, 40.7505)

BoundingBox = tuple[float, float, float, float]


def harvest_mapillary(
    settings: Settings,
    bbox: BoundingBox = NOMAD_DEMO_CORRIDOR_BBOX,
) -> int:
    if not settings.mapillary_token:
        raise RuntimeError("MAPILLARY_TOKEN is required for harvest_mapillary")

    images: dict[str, StreetImage] = {}
    with httpx.Client(
        base_url=MAPILLARY_GRAPH_URL,
        headers={"Authorization": f"OAuth {settings.mapillary_token}"},
        timeout=60.0,
    ) as client:
        for query_bbox in tile_bboxes(bbox):
            for image in _fetch_tile(client, query_bbox):
                images[image.mapillary_id] = image

    return upsert_street_images(
        settings.database_url,
        sorted(images.values(), key=lambda image: image.mapillary_id),
    )


def tile_bboxes(target_bbox: BoundingBox) -> list[BoundingBox]:
    west, south, east, north = _validate_bbox(target_bbox)
    queries: list[BoundingBox] = []
    for tile in mercantile.tiles(
        west,
        south,
        east,
        north,
        zooms=MAPILLARY_TILE_ZOOM,
    ):
        bounds = mercantile.bounds(tile)
        query_bbox = (
            max(west, bounds.west),
            max(south, bounds.south),
            min(east, bounds.east),
            min(north, bounds.north),
        )
        if query_bbox[0] >= query_bbox[2] or query_bbox[1] >= query_bbox[3]:
            continue
        if (
            query_bbox[2] - query_bbox[0] >= MAX_QUERY_SPAN_DEGREES
            or query_bbox[3] - query_bbox[1] >= MAX_QUERY_SPAN_DEGREES
        ):
            raise ValueError(f"Mapillary tile bbox is too large: {query_bbox}")
        queries.append(query_bbox)
    return queries


def _fetch_tile(client: httpx.Client, bbox: BoundingBox) -> list[StreetImage]:
    params = {
        "bbox": ",".join(f"{coordinate:.7f}" for coordinate in bbox),
        "fields": MAPILLARY_FIELDS,
        "limit": MAPILLARY_PAGE_SIZE,
    }
    images: dict[str, StreetImage] = {}
    seen_cursors: set[str] = set()

    while True:
        response = client.get("/images", params=params)
        response.raise_for_status()
        payload = response.json()
        for item in payload.get("data", []):
            image = _parse_image(item)
            if image is not None:
                images[image.mapillary_id] = image

        cursor = str(
            (payload.get("paging") or {}).get("cursors", {}).get("after") or ""
        )
        if not cursor or cursor in seen_cursors:
            break
        seen_cursors.add(cursor)
        params["after"] = cursor

    return list(images.values())


def _parse_image(payload: dict[str, Any]) -> StreetImage | None:
    mapillary_id = str(payload.get("id") or "").strip()
    geometry = payload.get("geometry")
    if not isinstance(geometry, dict):
        return None
    coordinates = geometry.get("coordinates") or []
    compass_angle = payload.get("compass_angle")
    captured_at = payload.get("captured_at")
    thumb_url = str(payload.get("thumb_1024_url") or "").strip()
    if (
        not mapillary_id
        or geometry.get("type") != "Point"
        or len(coordinates) < 2
        or compass_angle is None
        or captured_at is None
        or not thumb_url
    ):
        return None

    try:
        longitude = float(coordinates[0])
        latitude = float(coordinates[1])
        angle = float(compass_angle)
        observed_at = _parse_captured_at(captured_at)
    except (OSError, OverflowError, TypeError, ValueError):
        return None
    if not all(math.isfinite(value) for value in (longitude, latitude, angle)):
        return None
    if not (-180 <= longitude <= 180 and -90 <= latitude <= 90):
        return None

    return StreetImage(
        mapillary_id=mapillary_id,
        longitude=longitude,
        latitude=latitude,
        compass_angle=angle % 360.0,
        captured_at=observed_at,
        thumb_url=thumb_url,
    )


def _parse_captured_at(value: object) -> datetime:
    if isinstance(value, (int, float)):
        numeric_value = float(value)
        seconds = (
            numeric_value / 1_000.0
            if numeric_value >= 10_000_000_000
            else numeric_value
        )
        return datetime.fromtimestamp(seconds, timezone.utc)

    text = str(value).strip()
    if text.isdigit():
        return _parse_captured_at(int(text))
    parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def _validate_bbox(bbox: BoundingBox) -> BoundingBox:
    if len(bbox) != 4 or not all(math.isfinite(value) for value in bbox):
        raise ValueError("bbox must contain four finite coordinates")
    west, south, east, north = bbox
    if not (-180 <= west < east <= 180 and -90 <= south < north <= 90):
        raise ValueError("bbox must be ordered as west,south,east,north")
    return bbox
