from __future__ import annotations

from datetime import datetime, time, timezone
from typing import Any
from zoneinfo import ZoneInfo

import geopandas as gpd
import httpx
import redis
from shapely.geometry import Point, shape
from shapely.ops import unary_union

from beacon_pipeline.config import Settings
from beacon_pipeline.db import Reading, upsert_readings


NYC_COMMUNITY_DISTRICTS_URL = "https://data.cityofnewyork.us/resource/5crt-au7u.geojson"
POLLEN_FORECAST_URL = "https://pollen.googleapis.com/v1/forecast:lookup"
POLLEN_HAZARDS = {
    "TREE": "pollen_tree",
    "GRASS": "pollen_grass",
    "WEED": "pollen_weed",
}
_RESERVE_CALL_SCRIPT = """
local current = tonumber(redis.call('GET', KEYS[1]) or '0')
if current >= tonumber(ARGV[1]) then return 0 end
local next = redis.call('INCR', KEYS[1])
if next == 1 then redis.call('EXPIRE', KEYS[1], ARGV[2]) end
return next
"""


def ingest_pollen(settings: Settings) -> int:
    if not settings.google_maps_key:
        raise RuntimeError("GOOGLE_MAPS_KEY is required for ingest_pollen")

    quota = redis.Redis.from_url(settings.redis_url, decode_responses=True)
    headers = {"X-App-Token": settings.nyc_open_data_app_token} if settings.nyc_open_data_app_token else {}
    readings: list[Reading] = []
    with httpx.Client(timeout=30.0) as client:
        boundary_response = client.get(
            NYC_COMMUNITY_DISTRICTS_URL,
            params={"$limit": 100},
            headers=headers,
        )
        boundary_response.raise_for_status()
        boundary = unary_union(
            [shape(feature["geometry"]) for feature in boundary_response.json().get("features", [])]
        )
        points = build_pollen_lattice(boundary)
        points = _even_sample(points, settings.pollen_daily_call_budget)

        for latitude, longitude in points:
            if not _reserve_daily_call(quota, settings.pollen_daily_call_budget):
                break
            response = client.get(
                POLLEN_FORECAST_URL,
                params={
                    "key": settings.google_maps_key,
                    "location.latitude": latitude,
                    "location.longitude": longitude,
                    "days": 1,
                    "plantsDescription": "false",
                },
            )
            response.raise_for_status()
            readings.extend(_parse_forecast(response.json(), latitude, longitude))

    return upsert_readings(settings.database_url, readings)


def build_pollen_lattice(boundary: Any, spacing_m: float = 4_000.0) -> list[tuple[float, float]]:
    if boundary.is_empty:
        return []
    projected = gpd.GeoSeries([boundary], crs=4326).to_crs(6539).iloc[0]
    min_x, min_y, max_x, max_y = projected.bounds
    points: list[Point] = []
    x = min_x + spacing_m / 2.0
    while x <= max_x:
        y = min_y + spacing_m / 2.0
        while y <= max_y:
            point = Point(x, y)
            if projected.covers(point):
                points.append(point)
            y += spacing_m
        x += spacing_m

    wgs84 = gpd.GeoSeries(points, crs=6539).to_crs(4326)
    return [(point.y, point.x) for point in wgs84]


def _parse_forecast(payload: dict[str, Any], latitude: float, longitude: float) -> list[Reading]:
    daily = payload.get("dailyInfo") or []
    if not daily:
        return []
    day = daily[0]
    represented_date = day.get("date") or {}
    observed_at = datetime.combine(
        datetime(
            int(represented_date["year"]),
            int(represented_date["month"]),
            int(represented_date["day"]),
        ).date(),
        time.min,
        timezone.utc,
    )
    station_id = f"google-pollen:{latitude:.5f}:{longitude:.5f}"
    rows: list[Reading] = []
    for info in day.get("pollenTypeInfo", []):
        hazard = POLLEN_HAZARDS.get(str(info.get("code", "")).upper())
        if not hazard:
            continue
        index = info.get("indexInfo") or {}
        rows.append(
            Reading(
                hazard=hazard,
                station_id=station_id,
                observed_at=observed_at,
                source="google_pollen",
                value=float(index.get("value", 0)),
                unit="UPI",
            )
        )
    return rows


def _reserve_daily_call(client: redis.Redis, budget: int) -> bool:
    local_date = datetime.now(ZoneInfo("America/New_York")).date().isoformat()
    key = f"beacon:pollen:calls:{local_date}"
    result = client.eval(_RESERVE_CALL_SCRIPT, 1, key, budget, 172_800)
    return int(result) > 0


def _even_sample(points: list[tuple[float, float]], limit: int) -> list[tuple[float, float]]:
    if limit <= 0:
        return []
    if len(points) <= limit:
        return points
    if limit == 1:
        return [points[len(points) // 2]]
    return [points[round(index * (len(points) - 1) / (limit - 1))] for index in range(limit)]
