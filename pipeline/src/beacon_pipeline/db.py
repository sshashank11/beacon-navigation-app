from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass
from datetime import date, datetime

import psycopg


@dataclass(frozen=True)
class Reading:
    hazard: str
    station_id: str
    observed_at: datetime
    source: str
    value: float
    unit: str


@dataclass(frozen=True)
class NwsAlert:
    alert_id: str
    event: str
    headline: str | None
    severity: str | None
    urgency: str | None
    certainty: str | None
    onset: datetime | None
    expires_at: datetime | None
    description: str | None
    instruction: str | None
    updated_at: datetime


@dataclass(frozen=True)
class ConstructionPermit:
    permit_id: str
    source: str
    bin: str
    permit_type: str
    issued_at: date | None
    expires_at: date
    severity: int
    longitude: float
    latitude: float


def upsert_readings(database_url: str, readings: Iterable[Reading]) -> int:
    rows = list(readings)
    if not rows:
        return 0

    with psycopg.connect(database_url) as conn:
        with conn.cursor() as cur:
            cur.executemany(
                """
                INSERT INTO citywide_reading
                  (hazard, station_id, observed_at, source, value, unit)
                VALUES
                  (%s, %s, %s, %s, %s, %s)
                ON CONFLICT (hazard, station_id, observed_at)
                DO UPDATE SET
                  source = EXCLUDED.source,
                  value = EXCLUDED.value,
                  unit = EXCLUDED.unit
                """,
                [
                    (
                        row.hazard,
                        row.station_id,
                        row.observed_at,
                        row.source,
                        row.value,
                        row.unit,
                    )
                    for row in rows
                ],
            )
        conn.commit()
    return len(rows)


def replace_nws_alerts(database_url: str, alerts: Iterable[NwsAlert]) -> int:
    rows = list(alerts)
    with psycopg.connect(database_url) as conn:
        with conn.cursor() as cur:
            cur.execute("DELETE FROM nws_alert")
            cur.executemany(
                """
                INSERT INTO nws_alert
                  (id, event, headline, severity, urgency, certainty, onset,
                   expires_at, description, instruction, updated_at)
                VALUES
                  (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                """,
                [
                    (
                        row.alert_id,
                        row.event,
                        row.headline,
                        row.severity,
                        row.urgency,
                        row.certainty,
                        row.onset,
                        row.expires_at,
                        row.description,
                        row.instruction,
                        row.updated_at,
                    )
                    for row in rows
                ],
            )
        conn.commit()
    return len(rows)


def replace_construction_permits(
    database_url: str,
    permits: Iterable[ConstructionPermit],
    source: str,
) -> int:
    rows = list(permits)
    with psycopg.connect(database_url) as conn:
        with conn.cursor() as cur:
            cur.execute("DELETE FROM construction_permit WHERE source = %s", (source,))
            cur.executemany(
                """
                INSERT INTO construction_permit
                  (permit_id, source, bin, permit_type, issued_at, expires_at,
                   severity, geom)
                VALUES
                  (%s, %s, %s, %s, %s, %s, %s,
                   ST_SetSRID(ST_MakePoint(%s, %s), 4326))
                """,
                [
                    (
                        row.permit_id,
                        row.source,
                        row.bin,
                        row.permit_type,
                        row.issued_at,
                        row.expires_at,
                        row.severity,
                        row.longitude,
                        row.latitude,
                    )
                    for row in rows
                ],
            )
        conn.commit()
    return len(rows)
