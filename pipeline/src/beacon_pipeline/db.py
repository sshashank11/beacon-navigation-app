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


@dataclass(frozen=True)
class StreetImage:
    mapillary_id: str
    longitude: float
    latitude: float
    compass_angle: float
    captured_at: datetime
    thumb_url: str


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


def upsert_street_images(database_url: str, images: Iterable[StreetImage]) -> int:
    rows = list(images)
    if not rows:
        return 0

    mapillary_ids = list(dict.fromkeys(row.mapillary_id for row in rows))

    with psycopg.connect(database_url) as conn:
        with conn.cursor() as cur:
            cur.executemany(
                """
                INSERT INTO street_image
                  (mapillary_id, geom, compass_angle, captured_at, thumb_url)
                VALUES
                  (%s, ST_SetSRID(ST_MakePoint(%s, %s), 4326), %s, %s, %s)
                ON CONFLICT (mapillary_id)
                DO UPDATE SET
                  geom = EXCLUDED.geom,
                  compass_angle = EXCLUDED.compass_angle,
                  captured_at = EXCLUDED.captured_at,
                  thumb_url = EXCLUDED.thumb_url,
                  nearest_segment_id = NULL,
                  harvested_at = now()
                """,
                [
                    (
                        row.mapillary_id,
                        row.longitude,
                        row.latitude,
                        row.compass_angle,
                        row.captured_at,
                        row.thumb_url,
                    )
                    for row in rows
                ],
            )
            cur.execute(
                """
                WITH nearest AS (
                  SELECT
                    image.mapillary_id,
                    candidate.segment_id,
                    candidate.distance_m
                  FROM street_image image
                  CROSS JOIN LATERAL (
                    SELECT
                      segment.id AS segment_id,
                      ST_Distance(
                        image.geom::geography,
                        ST_ClosestPoint(segment.geom, image.geom)::geography
                      ) AS distance_m
                    FROM segment
                    ORDER BY segment.geom <-> image.geom
                    LIMIT 1
                  ) candidate
                  WHERE image.mapillary_id = ANY(%s)
                )
                UPDATE street_image image
                SET nearest_segment_id = nearest.segment_id
                FROM nearest
                WHERE image.mapillary_id = nearest.mapillary_id
                  AND nearest.distance_m <= 25
                """,
                (mapillary_ids,),
            )
            cur.execute(
                """
                SELECT count(*)
                FROM street_image
                WHERE mapillary_id = ANY(%s)
                  AND nearest_segment_id IS NOT NULL
                """,
                (mapillary_ids,),
            )
            accepted_count = int(cur.fetchone()[0])
            cur.execute(
                """
                DELETE FROM street_image
                WHERE mapillary_id = ANY(%s)
                  AND nearest_segment_id IS NULL
                """,
                (mapillary_ids,),
            )
        conn.commit()
    return accepted_count


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
