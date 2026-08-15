from __future__ import annotations

from datetime import datetime, timezone

import httpx

from beacon_pipeline.config import Settings
from beacon_pipeline.db import Reading, upsert_readings


def ingest_airnow(settings: Settings) -> int:
    if not settings.airnow_api_key:
        raise RuntimeError("AIRNOW_API_KEY is required for ingest_airnow")

    response = httpx.get(
        "https://www.airnowapi.org/aq/observation/zipCode/current/",
        params={
            "format": "application/json",
            "zipCode": "10007",
            "distance": 25,
            "API_KEY": settings.airnow_api_key,
        },
        timeout=30.0,
    )
    response.raise_for_status()

    observed_at = datetime.now(timezone.utc)
    readings: list[Reading] = []
    for row in response.json():
        parameter = str(row.get("ParameterName", "")).lower()
        hazard = {"pm2.5": "pm25", "ozone": "ozone", "no2": "no2"}.get(parameter)
        if not hazard:
            continue
        readings.append(
            Reading(
                hazard=f"{hazard}_aqi",
                station_id=f"airnow:{row.get('ReportingArea', 'nyc')}",
                observed_at=observed_at,
                source="airnow",
                value=float(row["AQI"]),
                unit="AQI",
            )
        )
    return upsert_readings(settings.database_url, readings)
