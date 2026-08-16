from __future__ import annotations

import json
import math
import subprocess
from collections.abc import Iterable, Iterator
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import TextIO

import psycopg
from shapely.geometry import LineString

from beacon_pipeline.osm import DEFAULT_OSM_DATA_DIR, osmium_command


DEFAULT_OSM_PATH = DEFAULT_OSM_DATA_DIR / "nyc.osm.pbf"
MAX_SEGMENT_LENGTH_M = 99.0
EARTH_RADIUS_M = 6_371_008.8

EXCLUDED_HIGHWAYS = {
    "abandoned",
    "bus_guideway",
    "construction",
    "motorway",
    "motorway_link",
    "planned",
    "platform",
    "proposed",
    "raceway",
    "razed",
}
BIKE_EXCLUDED_HIGHWAYS = {"corridor", "elevator", "escalator", "steps"}
DENIED_ACCESS = {"no", "private"}
ALLOWED_ACCESS = {"designated", "destination", "permissive", "yes"}
SIDEWALK_PRESENT = {"both", "left", "right", "separate", "yes"}


@dataclass(frozen=True)
class SegmentRow:
    osm_way_id: int
    seq: int
    geometry_ewkt: str
    highway_class: str
    has_sidewalk: bool | None


@dataclass(frozen=True)
class SegmentImportResult:
    segment_count: int
    way_count: int
    maximum_length_m: float
    average_length_m: float


def extract_segments(
    database_url: str,
    osm_path: Path = DEFAULT_OSM_PATH,
) -> SegmentImportResult:
    osm_path = osm_path.resolve()
    if not osm_path.is_file():
        raise FileNotFoundError(f"OSM extract does not exist: {osm_path}")

    with stream_osm_features(osm_path) as features:
        _replace_segments(database_url, segment_rows(features))
    return segment_statistics(database_url)


def segment_rows(records: Iterable[str]) -> Iterator[SegmentRow]:
    for record in records:
        payload = record.lstrip("\x1e").strip()
        if not payload:
            continue
        feature = json.loads(payload)
        feature_id = str(feature.get("id", ""))
        geometry = feature.get("geometry") or {}
        properties = feature.get("properties") or {}
        highway_class = properties.get("highway")
        if (
            not feature_id.startswith("w")
            or geometry.get("type") != "LineString"
            or not isinstance(highway_class, str)
            or not is_walk_or_bike_accessible(properties)
        ):
            continue

        way_id = int(feature_id[1:])
        coordinates = geometry.get("coordinates") or []
        for sequence, chunk in enumerate(split_coordinates(coordinates)):
            yield SegmentRow(
                osm_way_id=way_id,
                seq=sequence,
                geometry_ewkt=f"SRID=4326;{LineString(chunk).wkt}",
                highway_class=highway_class,
                has_sidewalk=sidewalk_value(properties),
            )


def split_coordinates(
    coordinates: list[list[float]],
    maximum_length_m: float = MAX_SEGMENT_LENGTH_M,
) -> Iterator[list[tuple[float, float]]]:
    if maximum_length_m <= 0:
        raise ValueError("maximum_length_m must be positive")
    if len(coordinates) < 2:
        return

    current = [_coordinate(coordinates[0])]
    distance_in_chunk = 0.0
    for raw_end in coordinates[1:]:
        start = current[-1]
        end = _coordinate(raw_end)
        leg_length = haversine_m(start, end)
        if leg_length == 0:
            continue

        while distance_in_chunk + leg_length > maximum_length_m:
            remaining = maximum_length_m - distance_in_chunk
            fraction = remaining / leg_length
            split_point = (
                start[0] + (end[0] - start[0]) * fraction,
                start[1] + (end[1] - start[1]) * fraction,
            )
            current.append(split_point)
            yield current
            current = [split_point]
            start = split_point
            leg_length -= remaining
            distance_in_chunk = 0.0

        current.append(end)
        distance_in_chunk += leg_length

    if len(current) >= 2 and distance_in_chunk > 0:
        yield current


def haversine_m(start: tuple[float, float], end: tuple[float, float]) -> float:
    start_lon, start_lat = (math.radians(value) for value in start)
    end_lon, end_lat = (math.radians(value) for value in end)
    delta_lon = end_lon - start_lon
    delta_lat = end_lat - start_lat
    haversine = (
        math.sin(delta_lat / 2) ** 2
        + math.cos(start_lat) * math.cos(end_lat) * math.sin(delta_lon / 2) ** 2
    )
    return 2 * EARTH_RADIUS_M * math.asin(math.sqrt(haversine))


def is_walk_or_bike_accessible(tags: dict[str, object]) -> bool:
    highway = str(tags.get("highway", ""))
    if not highway or highway in EXCLUDED_HIGHWAYS:
        return False

    general_access = str(tags.get("access", "")).lower()
    foot_access = str(tags.get("foot", "")).lower()
    bike_access = str(tags.get("bicycle", "")).lower()
    generally_denied = general_access in DENIED_ACCESS
    walkable = foot_access not in DENIED_ACCESS and (
        not generally_denied or foot_access in ALLOWED_ACCESS
    )
    bikeable = highway not in BIKE_EXCLUDED_HIGHWAYS and bike_access not in DENIED_ACCESS and (
        not generally_denied or bike_access in ALLOWED_ACCESS
    )
    return walkable or bikeable


def sidewalk_value(tags: dict[str, object]) -> bool | None:
    values = {
        str(tags.get(key, "")).lower()
        for key in ("sidewalk", "sidewalk:both", "sidewalk:left", "sidewalk:right")
    }
    if values & SIDEWALK_PRESENT:
        return True
    if "no" in values:
        return False
    return None


def segment_statistics(database_url: str) -> SegmentImportResult:
    with psycopg.connect(database_url) as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                SELECT
                  count(*)::bigint,
                  count(DISTINCT osm_way_id)::bigint,
                  coalesce(max(length_m), 0)::double precision,
                  coalesce(avg(length_m), 0)::double precision
                FROM segment
                """
            )
            row = cursor.fetchone()
    if row is None:
        raise RuntimeError("Segment statistics query returned no row")
    return SegmentImportResult(*row)


def _replace_segments(database_url: str, rows: Iterable[SegmentRow]) -> None:
    with psycopg.connect(database_url) as connection:
        with connection.cursor() as cursor:
            cursor.execute("TRUNCATE segment RESTART IDENTITY CASCADE")
            with cursor.copy(
                """
                COPY segment
                  (osm_way_id, seq, geom, highway_class, has_sidewalk)
                FROM STDIN
                """
            ) as copy:
                for row in rows:
                    copy.write_row((
                        row.osm_way_id,
                        row.seq,
                        row.geometry_ewkt,
                        row.highway_class,
                        row.has_sidewalk,
                    ))
        connection.commit()


@contextmanager
def stream_osm_features(osm_path: Path) -> Iterator[TextIO]:
    command = osmium_command(
        osm_path.parent,
        [
            "export",
            "--no-progress",
            "--geometry-types=linestring",
            "--add-unique-id=type_id",
            "--output-format=geojsonseq",
            osm_path.name,
        ],
    )
    process = subprocess.Popen(
        command,
        cwd=osm_path.parent,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
    )
    if process.stdout is None or process.stderr is None:
        process.kill()
        raise RuntimeError("Could not open Osmium output streams")

    try:
        yield process.stdout
    except BaseException:
        process.kill()
        process.wait()
        raise
    else:
        process.stdout.close()
        stderr = process.stderr.read()
        return_code = process.wait()
        if return_code != 0:
            raise RuntimeError(f"Osmium export failed ({return_code}): {stderr.strip()}")
    finally:
        process.stderr.close()


def _coordinate(raw: list[float]) -> tuple[float, float]:
    if len(raw) < 2:
        raise ValueError("OSM coordinate must contain longitude and latitude")
    return float(raw[0]), float(raw[1])
