from __future__ import annotations

import math
from contextlib import ExitStack
from dataclasses import dataclass
from pathlib import Path

import httpx
import psycopg
import rasterio
from rasterio.enums import Resampling
from rasterio.vrt import WarpedVRT


USGS_3DEP_BASE_URL = (
    "https://prd-tnm.s3.amazonaws.com/StagedProducts/Elevation/13/TIFF/current"
)
USGS_3DEP_TILES = (
    "n41w075/USGS_13_n41w075.tif",
    "n41w074/USGS_13_n41w074.tif",
)
DEFAULT_ELEVATION_DIR = Path(__file__).resolve().parents[3] / "data" / "elevation"
MAX_ABSOLUTE_GRADE_PCT = 20.0
SAMPLE_BATCH_SIZE = 10_000


@dataclass(frozen=True)
class ElevationResult:
    segment_count: int
    graded_count: int
    clamped_count: int
    minimum_grade_pct: float
    maximum_grade_pct: float


def enrich_segment_elevation(
    database_url: str,
    elevation_dir: Path = DEFAULT_ELEVATION_DIR,
    *,
    force_download: bool = False,
) -> ElevationResult:
    tile_paths = download_elevation_tiles(elevation_dir, force=force_download)
    with ElevationSampler(tile_paths) as sampler:
        clamped_count = _replace_grades(database_url, sampler)
    return elevation_statistics(database_url, clamped_count)


def download_elevation_tiles(
    elevation_dir: Path,
    *,
    force: bool = False,
) -> list[Path]:
    elevation_dir = elevation_dir.resolve()
    elevation_dir.mkdir(parents=True, exist_ok=True)
    paths: list[Path] = []
    with httpx.Client(follow_redirects=True, timeout=60.0) as client:
        for relative_path in USGS_3DEP_TILES:
            destination = elevation_dir / Path(relative_path).name
            if force or not _valid_raster(destination):
                _download(client, f"{USGS_3DEP_BASE_URL}/{relative_path}", destination)
            if not _valid_raster(destination):
                raise RuntimeError(f"Downloaded elevation tile is invalid: {destination}")
            paths.append(destination)
    return paths


class ElevationSampler:
    def __init__(self, raster_paths: list[Path]) -> None:
        if not raster_paths:
            raise ValueError("At least one elevation raster is required")
        self._raster_paths = raster_paths
        self._stack: ExitStack | None = None
        self._rasters: list[WarpedVRT] = []

    def __enter__(self) -> ElevationSampler:
        stack = ExitStack()
        try:
            for path in self._raster_paths:
                source = stack.enter_context(rasterio.open(path))
                self._rasters.append(stack.enter_context(WarpedVRT(
                    source,
                    crs="EPSG:4326",
                    resampling=Resampling.bilinear,
                )))
        except BaseException:
            stack.close()
            self._rasters.clear()
            raise
        self._stack = stack
        return self

    def __exit__(self, *_: object) -> None:
        if self._stack is not None:
            self._stack.close()
        self._stack = None
        self._rasters.clear()

    def sample(self, points: list[tuple[float, float]]) -> list[float | None]:
        if not self._rasters:
            raise RuntimeError("ElevationSampler must be used as a context manager")
        elevations: list[float | None] = [None] * len(points)
        unresolved = set(range(len(points)))
        for raster in self._rasters:
            indices = [
                index
                for index in unresolved
                if _inside(raster.bounds, points[index])
            ]
            values = raster.sample([points[index] for index in indices], masked=True)
            for index, value in zip(indices, values, strict=True):
                if value.mask.any():
                    continue
                elevation = float(value[0])
                if math.isfinite(elevation):
                    elevations[index] = elevation
                    unresolved.remove(index)
            if not unresolved:
                break
        return elevations


def calculate_grade_pct(
    start_elevation_m: float | None,
    end_elevation_m: float | None,
    length_m: float,
) -> tuple[float | None, bool]:
    if start_elevation_m is None or end_elevation_m is None or length_m <= 0:
        return None, False
    raw_grade = (end_elevation_m - start_elevation_m) / length_m * 100.0
    clamped_grade = max(-MAX_ABSOLUTE_GRADE_PCT, min(MAX_ABSOLUTE_GRADE_PCT, raw_grade))
    return clamped_grade, clamped_grade != raw_grade


def elevation_statistics(database_url: str, clamped_count: int = 0) -> ElevationResult:
    with psycopg.connect(database_url) as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                SELECT
                  count(*)::bigint,
                  count(grade_pct)::bigint,
                  coalesce(min(grade_pct), 0)::double precision,
                  coalesce(max(grade_pct), 0)::double precision
                FROM segment
                """
            )
            row = cursor.fetchone()
    if row is None:
        raise RuntimeError("Elevation statistics query returned no row")
    return ElevationResult(row[0], row[1], clamped_count, row[2], row[3])


def _replace_grades(database_url: str, sampler: ElevationSampler) -> int:
    with (
        psycopg.connect(database_url) as read_connection,
        psycopg.connect(database_url) as write_connection,
        read_connection.cursor(name="segment_elevation_source") as source,
        write_connection.cursor() as destination,
    ):
        source.execute(
            """
            SELECT
              id,
              length_m,
              ST_X(ST_StartPoint(geom)) AS start_lon,
              ST_Y(ST_StartPoint(geom)) AS start_lat,
              ST_X(ST_EndPoint(geom)) AS end_lon,
              ST_Y(ST_EndPoint(geom)) AS end_lat
            FROM segment
            ORDER BY ST_GeoHash(ST_Centroid(geom), 6), id
            """
        )
        destination.execute(
            """
            CREATE TEMP TABLE segment_grade_stage (
                segment_id BIGINT PRIMARY KEY,
                grade_pct REAL,
                was_clamped BOOLEAN NOT NULL
            ) ON COMMIT DROP
            """
        )
        with destination.copy(
            "COPY segment_grade_stage (segment_id, grade_pct, was_clamped) FROM STDIN"
        ) as copy:
            while rows := source.fetchmany(SAMPLE_BATCH_SIZE):
                starts = [(float(row[2]), float(row[3])) for row in rows]
                ends = [(float(row[4]), float(row[5])) for row in rows]
                start_elevations = sampler.sample(starts)
                end_elevations = sampler.sample(ends)
                for row, start_elevation, end_elevation in zip(
                    rows,
                    start_elevations,
                    end_elevations,
                    strict=True,
                ):
                    grade_pct, clamped = calculate_grade_pct(
                        start_elevation,
                        end_elevation,
                        float(row[1]),
                    )
                    copy.write_row((row[0], grade_pct, clamped))

        destination.execute(
            """
            UPDATE segment AS target
            SET grade_pct = source.grade_pct
            FROM segment_grade_stage AS source
            WHERE target.id = source.segment_id
            """
        )
        destination.execute(
            "SELECT count(*)::bigint FROM segment_grade_stage WHERE was_clamped"
        )
        clamped_count = destination.fetchone()[0]
        write_connection.commit()
        return clamped_count


def _download(client: httpx.Client, url: str, destination: Path) -> None:
    partial = destination.with_name(f"{destination.name}.part")
    with client.stream("GET", url) as response:
        response.raise_for_status()
        with partial.open("wb") as output:
            for chunk in response.iter_bytes(chunk_size=1024 * 1024):
                output.write(chunk)
    partial.replace(destination)


def _valid_raster(path: Path) -> bool:
    if not path.is_file() or path.stat().st_size < 1_000_000:
        return False
    try:
        with rasterio.open(path) as raster:
            return raster.count >= 1 and raster.crs is not None and raster.width > 100
    except rasterio.errors.RasterioError:
        return False


def _inside(bounds: rasterio.coords.BoundingBox, point: tuple[float, float]) -> bool:
    longitude, latitude = point
    return bounds.left <= longitude <= bounds.right and bounds.bottom <= latitude <= bounds.top
