from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

import httpx

from beacon_pipeline.config import NYC_BBOX, Settings
from beacon_pipeline.db import Reading, upsert_readings
from beacon_pipeline.http import get_with_retry


PARAMETER_TO_HAZARD = {
    "pm25": "pm25",
    "pm2.5": "pm25",
    "no2": "no2",
    "o3": "ozone",
}


def ingest_openaq(settings: Settings) -> int:
    if not settings.openaq_api_key:
        raise RuntimeError("OPENAQ_API_KEY is required for ingest_openaq")

    headers = {"X-API-Key": settings.openaq_api_key}
    bbox = ",".join(str(value) for value in NYC_BBOX)
    readings: list[Reading] = []

    with httpx.Client(base_url="https://api.openaq.org", headers=headers, timeout=30.0) as client:
        locations = get_with_retry(
            client,
            "/v3/locations",
            params={"bbox": bbox, "limit": 1000},
        )
        locations.raise_for_status()

        for location in locations.json().get("results", []):
            readings.extend(_read_location_hours(client, location))

    return upsert_readings(settings.database_url, readings)


def _read_location_hours(client: httpx.Client, location: dict[str, Any]) -> list[Reading]:
    rows: list[Reading] = []
    location_id = location.get("id")
    for sensor in location.get("sensors", []):
        parameter = _parameter_name(sensor)
        hazard = PARAMETER_TO_HAZARD.get(parameter)
        sensor_id = sensor.get("id")
        if not hazard or not sensor_id:
            continue

        response = get_with_retry(
            client,
            f"/v3/sensors/{sensor_id}/hours",
            params={"limit": 24},
        )
        response.raise_for_status()
        for result in response.json().get("results", []):
            value = result.get("value")
            period = result.get("period") or {}
            observed_at = period.get("datetimeFrom", {}).get("utc") or period.get("datetimeTo", {}).get("utc")
            if value is None or observed_at is None:
                continue
            rows.append(
                Reading(
                    hazard=hazard,
                    station_id=f"openaq:{location_id}:{sensor_id}",
                    observed_at=_parse_instant(observed_at),
                    source="openaq",
                    value=float(value),
                    unit=result.get("parameter", {}).get("units") or sensor.get("parameter", {}).get("units") or "",
                )
            )
    return rows


def _parameter_name(sensor: dict[str, Any]) -> str:
    parameter = sensor.get("parameter") or {}
    return str(parameter.get("name") or parameter.get("displayName") or "").lower()


def _parse_instant(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc)
