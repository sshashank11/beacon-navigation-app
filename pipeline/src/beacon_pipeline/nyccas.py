from __future__ import annotations

import math
import re
import shutil
import zipfile
from collections.abc import Iterable
from contextlib import ExitStack
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path, PurePosixPath
from statistics import fmean
from typing import Self
from urllib.parse import quote

import httpx
import psycopg
import rasterio
from rasterio.enums import Resampling
from rasterio.warp import calculate_default_transform, reproject

NYCCAS_DATASET_ID = "q68s-8qxv"
NYCCAS_METADATA_URL = f"https://data.cityofnewyork.us/api/views/{NYCCAS_DATASET_ID}"
NYCCAS_FILE_URL = f"https://data.cityofnewyork.us/api/views/{NYCCAS_DATASET_ID}/files"
TARGET_CRS = "EPSG:4326"
LONG_SEGMENT_THRESHOLD_M = 75.0
SAMPLE_BATCH_SIZE = 10_000

GRID_PATTERNS = {
    "pm25": re.compile(r"^aa(?P<year>\d+)_pm300m$", re.IGNORECASE),
    "no2": re.compile(r"^aa(?P<year>\d+)_no2300m$", re.IGNORECASE),
    "ozone": re.compile(r"^s(?P<year>\d+)_o3300m$", re.IGNORECASE),
}


@dataclass(frozen=True)
class NyccasDataset:
    blob_id: str
    filename: str


@dataclass(frozen=True)
class NyccasResult:
    segment_count: int
    sampled_count: int
    pm25_count: int
    no2_count: int
    ozone_count: int
    raster_years: dict[str, int]


def refresh_nyccas_scores(
    database_url: str,
    raster_dir: Path,
    *,
    force_download: bool = False,
) -> NyccasResult:
    raster_paths, raster_years = prepare_nyccas_rasters(
        raster_dir,
        force_download=force_download,
    )
    with NyccasSampler(raster_paths) as sampler:
        _replace_samples(database_url, sampler)
    _refresh_percentile_scores(database_url)
    return nyccas_statistics(database_url, raster_years)


def prepare_nyccas_rasters(
    raster_dir: Path,
    *,
    force_download: bool = False,
) -> tuple[dict[str, Path], dict[str, int]]:
    raster_dir = raster_dir.resolve()
    raster_dir.mkdir(parents=True, exist_ok=True)
    dataset = fetch_dataset_metadata()
    archive = raster_dir / dataset.filename

    if force_download or not _valid_archive(archive):
        _download_dataset(dataset, archive)

    source_dir = raster_dir / "source" / dataset.blob_id
    if force_download or not source_dir.is_dir():
        if source_dir.exists():
            shutil.rmtree(source_dir)
        source_dir.mkdir(parents=True)
        _extract_archive(archive, source_dir)

    source_paths, raster_years = discover_latest_grids(source_dir)
    output_paths: dict[str, Path] = {}
    for pollutant, source_path in source_paths.items():
        destination = raster_dir / f"{pollutant}.tif"
        reproject_raster(source_path, destination)
        output_paths[pollutant] = destination
    return output_paths, raster_years


def fetch_dataset_metadata() -> NyccasDataset:
    response = httpx.get(NYCCAS_METADATA_URL, follow_redirects=True, timeout=30.0)
    response.raise_for_status()
    metadata = response.json()
    blob_id = metadata.get("blobId")
    filename = metadata.get("blobFilename")
    if not isinstance(blob_id, str) or not isinstance(filename, str):
        raise TypeError("NYCCAS metadata did not include a downloadable file")
    return NyccasDataset(blob_id=blob_id, filename=Path(filename).name)


def discover_latest_grids(
    source_dir: Path,
) -> tuple[dict[str, Path], dict[str, int]]:
    candidates: dict[str, list[tuple[int, Path]]] = {
        pollutant: [] for pollutant in GRID_PATTERNS
    }
    for header in source_dir.rglob("hdr.adf"):
        for pollutant, pattern in GRID_PATTERNS.items():
            match = pattern.fullmatch(header.parent.name)
            if match:
                candidates[pollutant].append((int(match.group("year")), header))

    missing = [pollutant for pollutant, grids in candidates.items() if not grids]
    if missing:
        raise RuntimeError(f"NYCCAS archive is missing grids for: {', '.join(missing)}")

    latest = {
        pollutant: max(grids, key=lambda candidate: candidate[0])
        for pollutant, grids in candidates.items()
    }
    return (
        {pollutant: candidate[1] for pollutant, candidate in latest.items()},
        {pollutant: candidate[0] for pollutant, candidate in latest.items()},
    )


def reproject_raster(source_path: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    partial = destination.with_suffix(f"{destination.suffix}.part")
    try:
        with rasterio.open(source_path) as source:
            if source.crs is None:
                raise RuntimeError(f"NYCCAS raster has no CRS: {source_path}")
            transform, width, height = calculate_default_transform(
                source.crs,
                TARGET_CRS,
                source.width,
                source.height,
                *source.bounds,
            )
            profile = source.profile.copy()
            profile.update(
                driver="GTiff",
                crs=TARGET_CRS,
                transform=transform,
                width=width,
                height=height,
                compress="deflate",
            )
            with rasterio.open(partial, "w", **profile) as target:
                for band in range(1, source.count + 1):
                    reproject(
                        source=rasterio.band(source, band),
                        destination=rasterio.band(target, band),
                        src_transform=source.transform,
                        src_crs=source.crs,
                        src_nodata=source.nodata,
                        dst_transform=transform,
                        dst_crs=TARGET_CRS,
                        dst_nodata=source.nodata,
                        resampling=Resampling.bilinear,
                    )
        partial.replace(destination)
    finally:
        partial.unlink(missing_ok=True)


class NyccasSampler:
    def __init__(self, raster_paths: dict[str, Path]) -> None:
        missing = set(GRID_PATTERNS) - set(raster_paths)
        if missing:
            raise ValueError(f"Missing NYCCAS rasters: {', '.join(sorted(missing))}")
        self._raster_paths = raster_paths
        self._stack: ExitStack | None = None
        self._rasters: dict[str, rasterio.DatasetReader] = {}

    def __enter__(self) -> Self:
        stack = ExitStack()
        try:
            for pollutant, path in self._raster_paths.items():
                raster = stack.enter_context(rasterio.open(path))
                if raster.crs is None or raster.crs.to_epsg() != 4326:
                    raise RuntimeError(f"NYCCAS raster must use EPSG:4326: {path}")
                self._rasters[pollutant] = raster
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

    def sample(
        self,
        points_by_segment: list[list[tuple[float, float]]],
    ) -> list[dict[str, float | None]]:
        if not self._rasters:
            raise RuntimeError("NyccasSampler must be used as a context manager")

        flat_points = [
            point for segment_points in points_by_segment for point in segment_points
        ]
        offsets: list[tuple[int, int]] = []
        offset = 0
        for segment_points in points_by_segment:
            offsets.append((offset, offset + len(segment_points)))
            offset += len(segment_points)

        pollutant_values: dict[str, list[float | None]] = {}
        for pollutant, raster in self._rasters.items():
            values: list[float | None] = []
            for value in raster.sample(flat_points, masked=True):
                values.append(_sample_value(value))
            pollutant_values[pollutant] = values

        return [
            {
                pollutant: mean_valid(values[start:end])
                for pollutant, values in pollutant_values.items()
            }
            for start, end in offsets
        ]


def sample_positions(length_m: float) -> tuple[float, ...]:
    if length_m >= LONG_SEGMENT_THRESHOLD_M:
        return (0.25, 0.5, 0.75)
    return (0.5,)


def mean_valid(values: Iterable[float | None]) -> float | None:
    valid = [value for value in values if value is not None and math.isfinite(value)]
    return fmean(valid) if valid else None


def nyccas_statistics(
    database_url: str,
    raster_years: dict[str, int],
) -> NyccasResult:
    with psycopg.connect(database_url) as connection, connection.cursor() as cursor:
        cursor.execute(
            """
                SELECT
                  (SELECT count(*)::bigint FROM segment),
                  count(*)::bigint,
                  count(pm25_raw)::bigint,
                  count(no2_raw)::bigint,
                  count(ozone_raw)::bigint
                FROM segment_nyccas_sample
                """
        )
        row = cursor.fetchone()
    if row is None:
        raise RuntimeError("NYCCAS statistics query returned no row")
    return NyccasResult(*row, raster_years=raster_years)


def _replace_samples(database_url: str, sampler: NyccasSampler) -> None:
    with (
        psycopg.connect(database_url) as read_connection,
        psycopg.connect(database_url) as write_connection,
        read_connection.cursor(name="segment_nyccas_source") as source,
        write_connection.cursor() as destination,
    ):
        source.execute(
            """
            SELECT
              id,
              length_m,
              ST_X(ST_LineInterpolatePoint(geom, 0.25)),
              ST_Y(ST_LineInterpolatePoint(geom, 0.25)),
              ST_X(ST_LineInterpolatePoint(geom, 0.50)),
              ST_Y(ST_LineInterpolatePoint(geom, 0.50)),
              ST_X(ST_LineInterpolatePoint(geom, 0.75)),
              ST_Y(ST_LineInterpolatePoint(geom, 0.75))
            FROM segment
            ORDER BY ST_GeoHash(ST_Centroid(geom), 6), id
            """
        )
        destination.execute("TRUNCATE segment_nyccas_sample")
        sampled_at = datetime.now(UTC)
        with destination.copy(
            """
            COPY segment_nyccas_sample
              (segment_id, pm25_raw, no2_raw, ozone_raw, sampled_at)
            FROM STDIN
            """
        ) as copy:
            while rows := source.fetchmany(SAMPLE_BATCH_SIZE):
                points = [_sample_points(row) for row in rows]
                samples = sampler.sample(points)
                for row, values in zip(rows, samples, strict=True):
                    copy.write_row(
                        (
                            row[0],
                            values["pm25"],
                            values["no2"],
                            values["ozone"],
                            sampled_at,
                        )
                    )
        write_connection.commit()


def _refresh_percentile_scores(database_url: str) -> None:
    with psycopg.connect(database_url) as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                WITH
                pm25 AS (
                  SELECT segment_id,
                         PERCENT_RANK() OVER (ORDER BY pm25_raw) * 100 AS score
                  FROM segment_nyccas_sample
                  WHERE pm25_raw IS NOT NULL
                ),
                no2 AS (
                  SELECT segment_id,
                         PERCENT_RANK() OVER (ORDER BY no2_raw) * 100 AS score
                  FROM segment_nyccas_sample
                  WHERE no2_raw IS NOT NULL
                ),
                ozone AS (
                  SELECT segment_id,
                         PERCENT_RANK() OVER (ORDER BY ozone_raw) * 100 AS score
                  FROM segment_nyccas_sample
                  WHERE ozone_raw IS NOT NULL
                )
                INSERT INTO segment_static_score
                  (segment_id, pm25_prior, no2_prior, ozone_prior, computed_at)
                SELECT
                  sample.segment_id,
                  pm25.score,
                  no2.score,
                  ozone.score,
                  now()
                FROM segment_nyccas_sample AS sample
                LEFT JOIN pm25 USING (segment_id)
                LEFT JOIN no2 USING (segment_id)
                LEFT JOIN ozone USING (segment_id)
                ON CONFLICT (segment_id) DO UPDATE SET
                  pm25_prior = EXCLUDED.pm25_prior,
                  no2_prior = EXCLUDED.no2_prior,
                  ozone_prior = EXCLUDED.ozone_prior,
                  computed_at = EXCLUDED.computed_at
                """
            )
        connection.commit()


def _sample_points(row: tuple[object, ...]) -> list[tuple[float, float]]:
    by_position = {
        0.25: (float(row[2]), float(row[3])),
        0.5: (float(row[4]), float(row[5])),
        0.75: (float(row[6]), float(row[7])),
    }
    return [by_position[position] for position in sample_positions(float(row[1]))]


def _sample_value(value: object) -> float | None:
    if getattr(value, "mask", False).any():
        return None
    numeric = float(value[0])
    return numeric if math.isfinite(numeric) else None


def _download_dataset(dataset: NyccasDataset, destination: Path) -> None:
    url = f"{NYCCAS_FILE_URL}/{dataset.blob_id}?download=true&filename={quote(dataset.filename)}"
    partial = destination.with_suffix(f"{destination.suffix}.part")
    try:
        with httpx.stream("GET", url, follow_redirects=True, timeout=60.0) as response:
            response.raise_for_status()
            with partial.open("wb") as output:
                for chunk in response.iter_bytes(chunk_size=1024 * 1024):
                    output.write(chunk)
        if not _valid_archive(partial):
            raise RuntimeError("NYCCAS download is not a valid ZIP archive")
        partial.replace(destination)
    finally:
        partial.unlink(missing_ok=True)


def _extract_archive(archive: Path, destination: Path) -> None:
    with zipfile.ZipFile(archive) as source:
        for member in source.infolist():
            relative = PurePosixPath(member.filename)
            if relative.is_absolute() or ".." in relative.parts:
                raise RuntimeError(f"Unsafe path in NYCCAS archive: {member.filename}")
        source.extractall(destination)


def _valid_archive(path: Path) -> bool:
    if not path.is_file() or path.stat().st_size < 1_000_000:
        return False
    try:
        with zipfile.ZipFile(path) as archive:
            return archive.testzip() is None
    except zipfile.BadZipFile:
        return False
