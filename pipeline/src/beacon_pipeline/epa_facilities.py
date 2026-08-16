from __future__ import annotations

import csv
import math
import re
from collections.abc import Iterable, Iterator, Mapping
from dataclasses import dataclass
from pathlib import Path

import httpx
import psycopg

from beacon_pipeline.config import NYC_BBOX, Settings

DEFAULT_TRI_REPORTING_YEAR = 2024
TRI_DOWNLOAD_URL = (
    "https://data.epa.gov/efservice/downloads/tri/"
    "mv_tri_basic_download/{year}_NY/csv"
)
ECHO_QUERY_URL = (
    "https://echodata.epa.gov/echo/echo_rest_services.get_facilities"
)
ECHO_DOWNLOAD_URL = (
    "https://echodata.epa.gov/echo/echo_rest_services.get_download"
)
ECHO_COLUMNS = "1,4,6,17,18,19,21,22,23,26,27"
FACILITY_BBOX_PADDING_DEGREES = 0.02
INDUSTRIAL_RADIUS_M = 1_000.0
INDUSTRIAL_DECAY_M = 300.0
FACILITY_DEDUPE_DISTANCE_M = 25.0
INDUSTRIAL_PROGRAMS = frozenset({"CAA", "CWA", "RMP", "TRI", "EIS"})


@dataclass(frozen=True)
class IndustrialFacility:
    source: str
    source_id: str
    name: str
    programs: tuple[str, ...]
    state: str
    longitude: float
    latitude: float


@dataclass(frozen=True)
class IndustrialScoreResult:
    facility_count: int
    segment_count: int
    exposed_segment_count: int
    maximum_raw_kernel: float


def refresh_industrial_scores(
    settings: Settings,
    *,
    data_dir: Path | None = None,
    reporting_year: int | None = None,
    force_download: bool = False,
) -> IndustrialScoreResult:
    source_dir = (data_dir or settings.epa_data_dir).resolve()
    year = reporting_year or settings.tri_reporting_year
    tri_path = source_dir / f"tri-{year}-ny.csv"
    echo_path = source_dir / "echo-active-ny.csv"

    with httpx.Client(follow_redirects=True, timeout=120.0) as client:
        if force_download or not tri_path.is_file():
            _download_tri(client, tri_path, year)
        if force_download or not echo_path.is_file():
            _download_echo(client, echo_path)

    facilities = load_industrial_facilities(tri_path, echo_path)
    if not facilities:
        raise RuntimeError("EPA TRI and ECHO downloads contained no NYC facilities")
    _replace_facilities(settings.database_url, facilities)
    _replace_industrial_samples(settings.database_url)
    _refresh_industrial_percentiles(settings.database_url)
    return industrial_score_statistics(settings.database_url, len(facilities))


def load_industrial_facilities(
    tri_path: Path,
    echo_path: Path,
) -> list[IndustrialFacility]:
    facilities: dict[tuple[str, str], IndustrialFacility] = {}
    for path, parser in (
        (tri_path, parse_tri_facilities),
        (echo_path, parse_echo_facilities),
    ):
        with path.open("r", encoding="utf-8-sig", newline="", errors="replace") as file:
            for facility in parser(csv.DictReader(file)):
                facilities[(facility.source, facility.source_id)] = facility
    return list(facilities.values())


def parse_tri_facilities(
    rows: Iterable[Mapping[str, object]],
) -> Iterator[IndustrialFacility]:
    seen: set[str] = set()
    for raw_row in rows:
        row = _normalized_row(raw_row)
        state = _value(row, "st", "state")
        source_id = _value(row, "frsid", "trifd", "trifacilityid")
        name = _value(row, "facilityname")
        longitude = _coordinate(row, "longitude")
        latitude = _coordinate(row, "latitude")
        if (
            state != "NY"
            or not source_id
            or not name
            or source_id in seen
            or longitude is None
            or latitude is None
            or not _near_nyc(longitude, latitude)
        ):
            continue
        seen.add(source_id)
        yield IndustrialFacility(
            source="TRI",
            source_id=source_id,
            name=name,
            programs=("TRI",),
            state=state,
            longitude=longitude,
            latitude=latitude,
        )


def parse_echo_facilities(
    rows: Iterable[Mapping[str, object]],
) -> Iterator[IndustrialFacility]:
    program_fields = {
        "airids": "CAA",
        "npdesids": "CWA",
        "rcraids": "RCRA",
        "rmpids": "RMP",
        "triids": "TRI",
        "eisids": "EIS",
    }
    for raw_row in rows:
        row = _normalized_row(raw_row)
        programs = tuple(
            program for field, program in program_fields.items() if _value(row, field)
        )
        source_id = _value(row, "registryid")
        name = _value(row, "facname", "facilityname")
        state = _value(row, "facstate", "state")
        longitude = _coordinate(row, "faclong", "longitude")
        latitude = _coordinate(row, "faclat", "latitude")
        if (
            state != "NY"
            or not source_id
            or not name
            or not INDUSTRIAL_PROGRAMS.intersection(programs)
            or longitude is None
            or latitude is None
            or not _near_nyc(longitude, latitude)
        ):
            continue
        yield IndustrialFacility(
            source="ECHO",
            source_id=source_id,
            name=name,
            programs=programs,
            state=state,
            longitude=longitude,
            latitude=latitude,
        )


def distance_decay(distance_m: float, decay_m: float = INDUSTRIAL_DECAY_M) -> float:
    if distance_m < 0:
        raise ValueError("distance_m must not be negative")
    if decay_m <= 0:
        raise ValueError("decay_m must be positive")
    return math.exp(-distance_m / decay_m)


def industrial_score_statistics(
    database_url: str,
    facility_count: int | None = None,
) -> IndustrialScoreResult:
    with psycopg.connect(database_url) as connection, connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT
              (SELECT count(*)::bigint FROM industrial_facility),
              count(*)::bigint,
              count(*) FILTER (WHERE facility_count > 0)::bigint,
              coalesce(max(raw_kernel), 0)::double precision
            FROM segment_industrial_sample
            """
        )
        row = cursor.fetchone()
    if row is None:
        raise RuntimeError("Industrial score statistics query returned no row")
    if facility_count is not None and row[0] != facility_count:
        raise RuntimeError("EPA facility load count did not match the database")
    return IndustrialScoreResult(*row)


def _download_tri(client: httpx.Client, destination: Path, year: int) -> None:
    response = client.get(TRI_DOWNLOAD_URL.format(year=year))
    response.raise_for_status()
    _write_download(destination, response.content)


def _download_echo(client: httpx.Client, destination: Path) -> None:
    query = client.get(
        ECHO_QUERY_URL,
        params={"output": "JSON", "p_st": "NY", "p_act": "Y"},
    )
    query.raise_for_status()
    payload = query.json()
    try:
        query_id = str(payload["Results"]["QueryID"])
    except (KeyError, TypeError) as exception:
        raise RuntimeError("ECHO facility query did not return a query ID") from exception

    download = client.get(
        ECHO_DOWNLOAD_URL,
        params={"output": "CSV", "qid": query_id, "qcolumns": ECHO_COLUMNS},
    )
    download.raise_for_status()
    _write_download(destination, download.content)


def _write_download(destination: Path, content: bytes) -> None:
    if not content:
        raise RuntimeError(f"EPA download was empty: {destination.name}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + ".tmp")
    temporary.write_bytes(content)
    temporary.replace(destination)


def _replace_facilities(
    database_url: str,
    facilities: Iterable[IndustrialFacility],
) -> None:
    rows = list(facilities)
    with psycopg.connect(database_url) as connection, connection.cursor() as cursor:
        cursor.execute(
            """
            CREATE TEMP TABLE industrial_facility_stage
              (LIKE industrial_facility INCLUDING DEFAULTS INCLUDING GENERATED
                                        INCLUDING CONSTRAINTS)
            ON COMMIT DROP
            """
        )
        with cursor.copy(
            """
            COPY industrial_facility_stage
              (source, source_id, name, programs, state, geom)
            FROM STDIN
            """
        ) as copy:
            for facility in rows:
                copy.write_row(
                    (
                        facility.source,
                        facility.source_id,
                        facility.name,
                        list(facility.programs),
                        facility.state,
                        f"SRID=4326;POINT({facility.longitude} {facility.latitude})",
                    )
                )
        cursor.execute("TRUNCATE industrial_facility")
        cursor.execute(
            """
            INSERT INTO industrial_facility
              (source, source_id, name, programs, state, geom)
            SELECT source, source_id, name, programs, state, geom
            FROM industrial_facility_stage
            """
        )
        cursor.execute("ANALYZE industrial_facility")
        connection.commit()


def _replace_industrial_samples(database_url: str) -> None:
    with psycopg.connect(database_url) as connection, connection.cursor() as cursor:
        cursor.execute("SET LOCAL work_mem = '256MB'")
        cursor.execute(
            """
            CREATE TEMP TABLE industrial_facility_cluster ON COMMIT DROP AS
            WITH clustered AS (
              SELECT
                ST_ClusterDBSCAN(geom_32618, %s, 1) OVER () AS cluster_id,
                geom_32618
              FROM industrial_facility
            )
            SELECT
              row_number() OVER ()::bigint AS id,
              ST_Centroid(ST_Collect(geom_32618)) AS geom
            FROM clustered
            GROUP BY cluster_id
            """,
            (FACILITY_DEDUPE_DISTANCE_M,),
        )
        cursor.execute(
            "CREATE INDEX industrial_facility_cluster_geom_gix "
            "ON industrial_facility_cluster USING GIST (geom)"
        )
        cursor.execute("ANALYZE industrial_facility_cluster")
        cursor.execute("TRUNCATE segment_industrial_sample")
        cursor.execute(
            """
            INSERT INTO segment_industrial_sample
              (segment_id, facility_count, nearest_facility_m, raw_kernel, computed_at)
            SELECT
              segment.id,
              nearby.facility_count,
              nearby.nearest_facility_m,
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
                count(facility.id)::integer AS facility_count,
                min(ST_Distance(segment.midpoint, facility.geom))::real
                  AS nearest_facility_m,
                coalesce(
                  sum(exp(-ST_Distance(segment.midpoint, facility.geom) / %s)),
                  0
                )::real AS raw_kernel
              FROM industrial_facility_cluster AS facility
              WHERE ST_DWithin(segment.midpoint, facility.geom, %s)
            ) AS nearby
            """,
            (INDUSTRIAL_DECAY_M, INDUSTRIAL_RADIUS_M),
        )
        connection.commit()


def _refresh_industrial_percentiles(database_url: str) -> None:
    with psycopg.connect(database_url) as connection, connection.cursor() as cursor:
        cursor.execute(
            """
            WITH ranked AS (
              SELECT
                segment_id,
                PERCENT_RANK() OVER (ORDER BY raw_kernel) * 100 AS industrial
              FROM segment_industrial_sample
            )
            INSERT INTO segment_static_score
              (segment_id, industrial_prox, computed_at)
            SELECT segment_id, industrial, now()
            FROM ranked
            ON CONFLICT (segment_id) DO UPDATE SET
              industrial_prox = EXCLUDED.industrial_prox,
              computed_at = EXCLUDED.computed_at
            """
        )
        connection.commit()


def _normalized_row(row: Mapping[str, object]) -> dict[str, object]:
    return {_normalize_header(key): value for key, value in row.items()}


def _normalize_header(value: str) -> str:
    without_position = re.sub(r"^\d+\.\s*", "", value.strip())
    return re.sub(r"[^a-z0-9]", "", without_position.lower())


def _value(row: Mapping[str, object], *keys: str) -> str:
    for key in keys:
        value = row.get(key)
        if value is not None and str(value).strip():
            return str(value).strip()
    return ""


def _coordinate(row: Mapping[str, object], *keys: str) -> float | None:
    value = _value(row, *keys)
    try:
        coordinate = float(value)
    except ValueError:
        return None
    return coordinate if math.isfinite(coordinate) else None


def _near_nyc(longitude: float, latitude: float) -> bool:
    west, south, east, north = NYC_BBOX
    padding = FACILITY_BBOX_PADDING_DEGREES
    return (
        west - padding <= longitude <= east + padding
        and south - padding <= latitude <= north + padding
    )
