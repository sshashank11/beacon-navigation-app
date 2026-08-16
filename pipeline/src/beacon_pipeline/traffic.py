from __future__ import annotations

import json
import math
from collections.abc import Iterable, Iterator
from dataclasses import dataclass
from pathlib import Path

import psycopg
from shapely.geometry import LineString

from beacon_pipeline.extract_segments import DEFAULT_OSM_PATH, stream_osm_features

TRAFFIC_CLASS_WEIGHTS = {
    "motorway": 5.0,
    "motorway_link": 5.0,
    "trunk": 4.0,
    "trunk_link": 4.0,
    "primary": 3.0,
    "primary_link": 3.0,
    "secondary": 2.0,
    "secondary_link": 2.0,
}
TRAFFIC_RADIUS_M = 1_000.0
TRAFFIC_DECAY_M = 300.0


@dataclass(frozen=True)
class TrafficRoad:
    osm_way_id: int
    highway_class: str
    proxy_weight: float
    geometry_ewkt: str


@dataclass(frozen=True)
class TrafficScoreResult:
    road_count: int
    segment_count: int
    exposed_segment_count: int
    maximum_raw_kernel: float


def refresh_traffic_scores(
    database_url: str,
    osm_path: Path = DEFAULT_OSM_PATH,
) -> TrafficScoreResult:
    osm_path = osm_path.resolve()
    if not osm_path.is_file():
        raise FileNotFoundError(f"OSM extract does not exist: {osm_path}")

    with stream_osm_features(osm_path) as records:
        road_count = _replace_traffic_roads(database_url, traffic_road_rows(records))
    if road_count == 0:
        raise RuntimeError("OSM extract contained no weighted traffic roads")
    _replace_traffic_samples(database_url)
    _refresh_traffic_percentiles(database_url)
    return traffic_score_statistics(database_url, road_count)


def traffic_road_rows(records: Iterable[str]) -> Iterator[TrafficRoad]:
    for record in records:
        payload = record.lstrip("\x1e").strip()
        if not payload:
            continue
        feature = json.loads(payload)
        feature_id = str(feature.get("id", ""))
        geometry = feature.get("geometry") or {}
        properties = feature.get("properties") or {}
        source_highway_class = str(properties.get("highway", "")).lower()
        proxy_weight = TRAFFIC_CLASS_WEIGHTS.get(source_highway_class)
        highway_class = source_highway_class.removesuffix("_link")
        coordinates = geometry.get("coordinates") or []
        if (
            not feature_id.startswith("w")
            or geometry.get("type") != "LineString"
            or proxy_weight is None
            or len(coordinates) < 2
        ):
            continue

        line = LineString(
            (float(coordinate[0]), float(coordinate[1]))
            for coordinate in coordinates
            if len(coordinate) >= 2
        )
        if line.is_empty or len(line.coords) < 2:
            continue
        yield TrafficRoad(
            osm_way_id=int(feature_id[1:]),
            highway_class=highway_class,
            proxy_weight=proxy_weight,
            geometry_ewkt=f"SRID=4326;{line.wkt}",
        )


def traffic_decay_score(
    roads: Iterable[tuple[float, float]],
    decay_m: float = TRAFFIC_DECAY_M,
) -> float:
    if decay_m <= 0:
        raise ValueError("decay_m must be positive")
    total = 0.0
    for weight, distance_m in roads:
        if weight < 0 or distance_m < 0:
            raise ValueError("traffic weight and distance must not be negative")
        total += weight * math.exp(-distance_m / decay_m)
    return total


def traffic_score_statistics(
    database_url: str,
    road_count: int | None = None,
) -> TrafficScoreResult:
    with psycopg.connect(database_url) as connection, connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT
              (SELECT count(*)::bigint FROM traffic_road),
              count(*)::bigint,
              count(*) FILTER (WHERE road_class_count > 0)::bigint,
              coalesce(max(raw_kernel), 0)::double precision
            FROM segment_traffic_sample
            """
        )
        row = cursor.fetchone()
    if row is None:
        raise RuntimeError("Traffic score statistics query returned no row")
    if road_count is not None and row[0] != road_count:
        raise RuntimeError("Traffic road load count did not match the database")
    return TrafficScoreResult(*row)


def _replace_traffic_roads(
    database_url: str,
    roads: Iterable[TrafficRoad],
) -> int:
    count = 0
    with psycopg.connect(database_url) as connection, connection.cursor() as cursor:
        cursor.execute(
            """
            CREATE TEMP TABLE traffic_road_stage
              (LIKE traffic_road INCLUDING DEFAULTS INCLUDING GENERATED
                                 INCLUDING CONSTRAINTS)
            ON COMMIT DROP
            """
        )
        with cursor.copy(
            """
            COPY traffic_road_stage
              (osm_way_id, highway_class, proxy_weight, geom)
            FROM STDIN
            """
        ) as copy:
            for road in roads:
                copy.write_row(
                    (
                        road.osm_way_id,
                        road.highway_class,
                        road.proxy_weight,
                        road.geometry_ewkt,
                    )
                )
                count += 1
        cursor.execute("TRUNCATE traffic_road")
        cursor.execute(
            """
            INSERT INTO traffic_road
              (osm_way_id, highway_class, proxy_weight, geom)
            SELECT osm_way_id, highway_class, proxy_weight, geom
            FROM traffic_road_stage
            """
        )
        cursor.execute("ANALYZE traffic_road")
        connection.commit()
    return count


def _replace_traffic_samples(database_url: str) -> None:
    with psycopg.connect(database_url) as connection, connection.cursor() as cursor:
        cursor.execute("SET LOCAL work_mem = '256MB'")
        cursor.execute("TRUNCATE segment_traffic_sample")
        cursor.execute(
            """
            INSERT INTO segment_traffic_sample
              (segment_id, road_class_count, nearest_road_m, raw_kernel, computed_at)
            SELECT
              segment.id,
              nearby.road_class_count,
              nearby.nearest_road_m,
              nearby.raw_kernel,
              now()
            FROM (
              SELECT
                id,
                ST_LineInterpolatePoint(ST_Transform(geom, 32618), 0.5) AS midpoint
              FROM segment
            ) AS segment
            CROSS JOIN LATERAL (
              SELECT
                count(*)::smallint AS road_class_count,
                min(distance_m)::real AS nearest_road_m,
                coalesce(sum(proxy_weight * exp(-distance_m / %s)), 0)::real
                  AS raw_kernel
              FROM (
                SELECT
                  highway_class,
                  max(proxy_weight) AS proxy_weight,
                  min(ST_Distance(segment.midpoint, road.geom_32618)) AS distance_m
                FROM traffic_road AS road
                WHERE ST_DWithin(segment.midpoint, road.geom_32618, %s)
                GROUP BY highway_class
              ) AS nearest_by_class
            ) AS nearby
            """,
            (TRAFFIC_DECAY_M, TRAFFIC_RADIUS_M),
        )
        connection.commit()


def _refresh_traffic_percentiles(database_url: str) -> None:
    with psycopg.connect(database_url) as connection, connection.cursor() as cursor:
        cursor.execute(
            """
            WITH ranked AS (
              SELECT
                segment_id,
                PERCENT_RANK() OVER (ORDER BY raw_kernel) * 100 AS traffic
              FROM segment_traffic_sample
            )
            INSERT INTO segment_static_score
              (segment_id, traffic_prox, computed_at)
            SELECT segment_id, traffic, now()
            FROM ranked
            ON CONFLICT (segment_id) DO UPDATE SET
              traffic_prox = EXCLUDED.traffic_prox,
              computed_at = EXCLUDED.computed_at
            """
        )
        connection.commit()
