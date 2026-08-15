from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

import httpx

from beacon_pipeline.config import Settings
from beacon_pipeline.db import NwsAlert, Reading, replace_nws_alerts, upsert_readings


NYC_POINT = (40.7128, -74.0060)


def ingest_nws(settings: Settings) -> int:
    headers = {
        "User-Agent": settings.nws_user_agent,
        "Accept": "application/geo+json",
    }
    with httpx.Client(base_url="https://api.weather.gov", headers=headers, timeout=30.0) as client:
        point = client.get(f"/points/{NYC_POINT[0]},{NYC_POINT[1]}")
        point.raise_for_status()
        forecast_url = point.json()["properties"]["forecastHourly"]
        forecast = client.get(forecast_url)
        forecast.raise_for_status()
        alerts_response = client.get(
            "/alerts/active",
            params={"point": f"{NYC_POINT[0]},{NYC_POINT[1]}"},
        )
        alerts_response.raise_for_status()

    period = forecast.json()["properties"]["periods"][0]
    readings = _period_to_readings(period)
    alerts = _parse_alerts(alerts_response.json())
    reading_count = upsert_readings(settings.database_url, readings)
    alert_count = replace_nws_alerts(settings.database_url, alerts)
    return reading_count + alert_count


def _period_to_readings(period: dict[str, Any]) -> list[Reading]:
    observed_at = datetime.fromisoformat(period["startTime"]).astimezone(timezone.utc)
    temperature = float(period["temperature"])
    if period.get("temperatureUnit", "F").upper() == "F":
        temperature = _fahrenheit_to_celsius(temperature)
    rows = [Reading("heat", "nws:nyc", observed_at, "nws", temperature, "C")]
    humidity = period.get("relativeHumidity", {}).get("value")
    if humidity is not None:
        rows.append(Reading("humidity", "nws:nyc", observed_at, "nws", float(humidity), "%"))
    wind_speed = _first_number(period.get("windSpeed", ""))
    if wind_speed is not None:
        rows.append(Reading("wind_speed", "nws:nyc", observed_at, "nws", wind_speed, "mph"))
    wind_bearing = _wind_bearing(period.get("windDirection", ""))
    if wind_bearing is not None:
        rows.append(Reading("wind_bearing", "nws:nyc", observed_at, "nws", wind_bearing, "deg"))
    return rows


def _parse_alerts(payload: dict[str, Any]) -> list[NwsAlert]:
    rows: list[NwsAlert] = []
    for feature in payload.get("features", []):
        properties = feature.get("properties") or {}
        alert_id = properties.get("id") or feature.get("id")
        event = properties.get("event")
        if not alert_id or not event:
            continue
        rows.append(
            NwsAlert(
                alert_id=str(alert_id),
                event=str(event),
                headline=properties.get("headline"),
                severity=properties.get("severity"),
                urgency=properties.get("urgency"),
                certainty=properties.get("certainty"),
                onset=_parse_optional_instant(properties.get("onset")),
                expires_at=_parse_optional_instant(properties.get("expires")),
                description=properties.get("description"),
                instruction=properties.get("instruction"),
                updated_at=_parse_optional_instant(properties.get("sent")) or datetime.now(timezone.utc),
            )
        )
    return rows


def _fahrenheit_to_celsius(value: float | int) -> float:
    return (float(value) - 32.0) * 5.0 / 9.0


def _first_number(value: str) -> float | None:
    for token in value.replace("to", " ").split():
        try:
            return float(token)
        except ValueError:
            continue
    return None


def _parse_optional_instant(value: str | None) -> datetime | None:
    if not value:
        return None
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc)


def _wind_bearing(direction: str) -> float | None:
    bearings = {
        "N": 0.0,
        "NNE": 22.5,
        "NE": 45.0,
        "ENE": 67.5,
        "E": 90.0,
        "ESE": 112.5,
        "SE": 135.0,
        "SSE": 157.5,
        "S": 180.0,
        "SSW": 202.5,
        "SW": 225.0,
        "WSW": 247.5,
        "W": 270.0,
        "WNW": 292.5,
        "NW": 315.0,
        "NNW": 337.5,
    }
    return bearings.get(direction.upper())
