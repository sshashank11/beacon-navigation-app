from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

import geopandas as gpd
import numpy as np
import psycopg
import rasterio
from rasterio.features import shapes
from shapely.geometry import shape
from shapely.ops import unary_union

from beacon_pipeline.config import Settings


MAX_HAZARD_FIELDS = 20
ROUTABLE_RASTER_HAZARDS = {"pm25", "ozone", "no2"}


@dataclass(frozen=True)
class HazardMean:
    hazard: str
    value: float
    observed_at: datetime


@dataclass(frozen=True)
class HazardBand:
    hazard: str
    observed_at: datetime
    band_min: float
    band_max: float
    severity: int
    geometry_wkt: str


def build_hazard_fields(settings: Settings) -> int:
    means = {mean.hazard: mean for mean in _latest_citywide_means(settings.database_url)}
    bands: list[HazardBand] = []

    if settings.nyccas_raster_dir.exists():
        for raster_path in sorted(settings.nyccas_raster_dir.glob("*.tif")):
            hazard = raster_path.stem.lower()
            if hazard not in ROUTABLE_RASTER_HAZARDS or hazard not in means:
                continue
            bands.extend(_bands_from_raster(raster_path, means[hazard]))

    bands.extend(_construction_bands(settings.database_url))
    return _write_hazard_fields(
        settings.database_url,
        _prioritize_hazard_fields(bands),
    )


def _prioritize_hazard_fields(bands: list[HazardBand]) -> list[HazardBand]:
    return sorted(
        bands,
        key=lambda band: (-band.severity, band.hazard, -band.band_max),
    )[:MAX_HAZARD_FIELDS]


def refresh_construction_scores(database_url: str) -> int:
    with psycopg.connect(database_url) as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT to_regclass('public.segment'),
                       to_regclass('public.segment_slow_score')
                """
            )
            segment_table, score_table = cur.fetchone()
            if segment_table is None or score_table is None:
                raise RuntimeError(
                    "segment and segment_slow_score are required before construction scores can be refreshed"
                )
            cur.execute(
                """
                INSERT INTO segment_slow_score (segment_id, construction, valid_for_date)
                SELECT
                  segment.id,
                  COALESCE(MAX(permit.severity) * 25.0, 0.0),
                  CURRENT_DATE
                FROM segment
                LEFT JOIN construction_permit permit
                  ON permit.expires_at >= CURRENT_DATE
                 AND ST_DWithin(
                       segment.geom::geography,
                       permit.geom::geography,
                       40.0
                     )
                GROUP BY segment.id
                ON CONFLICT (segment_id)
                DO UPDATE SET
                  construction = EXCLUDED.construction,
                  valid_for_date = EXCLUDED.valid_for_date
                """
            )
            count = cur.rowcount
        conn.commit()
    return count


def _bands_from_raster(raster_path: Path, mean: HazardMean) -> list[HazardBand]:
    with rasterio.open(raster_path) as dataset:
        if dataset.crs is None:
            raise ValueError(f"Hazard raster has no CRS: {raster_path}")
        prior = dataset.read(1, masked=True).astype("float32")
        valid_mask = ~np.ma.getmaskarray(prior) & np.isfinite(prior.filled(np.nan))
        prior_values = np.asarray(prior)[valid_mask]
        if prior_values.size == 0:
            return []

        annual_mean = float(np.mean(prior_values))
        if annual_mean <= 0:
            return []
        temporal_scalar = max(0.1, min(mean.value / annual_mean, 10.0))
        scaled = np.asarray(prior) * temporal_scalar
        thresholds = np.percentile(prior_values, [25, 50, 75])
        severity_grid = np.digitize(scaled, thresholds, right=False).astype("uint8") + 1

        bands: list[HazardBand] = []
        for severity in range(1, 5):
            severity_mask = valid_mask & (severity_grid == severity)
            if not severity_mask.any():
                continue
            polygons = [
                shape(geometry)
                for geometry, value in shapes(
                    severity_grid,
                    mask=severity_mask,
                    transform=dataset.transform,
                )
                if int(value) == severity
            ]
            if not polygons:
                continue
            merged = unary_union(polygons)
            projected = gpd.GeoSeries([merged], crs=dataset.crs).to_crs(4326).iloc[0]
            values = scaled[severity_mask]
            bands.append(
                HazardBand(
                    hazard=mean.hazard,
                    observed_at=mean.observed_at,
                    band_min=float(np.min(values)),
                    band_max=float(np.max(values)),
                    severity=severity,
                    geometry_wkt=projected.wkt,
                )
            )
        return bands


def _latest_citywide_means(database_url: str) -> list[HazardMean]:
    with psycopg.connect(database_url) as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                WITH latest AS (
                  SELECT DISTINCT ON (hazard, station_id)
                    hazard, station_id, value, observed_at
                  FROM citywide_reading
                  WHERE observed_at >= now() - interval '24 hours'
                    AND source <> 'airnow'
                  ORDER BY hazard, station_id, observed_at DESC
                )
                SELECT hazard, avg(value), max(observed_at)
                FROM latest
                GROUP BY hazard
                """
            )
            return [
                HazardMean(str(row[0]), float(row[1]), row[2])
                for row in cur.fetchall()
            ]


def _construction_bands(database_url: str) -> list[HazardBand]:
    with psycopg.connect(database_url) as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT
                  severity,
                  ST_AsText(
                    ST_Multi(
                      ST_CollectionExtract(
                        ST_UnaryUnion(
                          ST_Collect(ST_Buffer(geom::geography, 40.0)::geometry)
                        ),
                        3
                      )
                    )
                  )
                FROM construction_permit
                WHERE expires_at >= CURRENT_DATE
                GROUP BY severity
                ORDER BY severity
                """
            )
            observed_at = datetime.now(timezone.utc)
            return [
                HazardBand(
                    hazard="construction",
                    observed_at=observed_at,
                    band_min=float((int(row[0]) - 1) * 25),
                    band_max=float(int(row[0]) * 25),
                    severity=int(row[0]),
                    geometry_wkt=str(row[1]),
                )
                for row in cur.fetchall()
                if row[1]
            ]


def _write_hazard_fields(database_url: str, bands: list[HazardBand]) -> int:
    if not bands:
        return 0

    snapshots = {(band.hazard, band.observed_at) for band in bands}
    with psycopg.connect(database_url) as conn:
        with conn.cursor() as cur:
            cur.executemany(
                "DELETE FROM hazard_field WHERE hazard = %s AND observed_at = %s",
                list(snapshots),
            )
            cur.executemany(
                """
                INSERT INTO hazard_field
                  (hazard, observed_at, band_min, band_max, severity, geom)
                VALUES
                  (%s, %s, %s, %s, %s,
                   ST_Multi(
                     ST_CollectionExtract(
                       ST_MakeValid(
                         ST_Transform(
                           ST_SimplifyPreserveTopology(
                             ST_Transform(ST_GeomFromText(%s, 4326), 3857),
                             50.0
                           ),
                           4326
                         )
                       ),
                       3
                     )
                   ))
                """,
                [
                    (
                        band.hazard,
                        band.observed_at,
                        band.band_min,
                        band.band_max,
                        band.severity,
                        band.geometry_wkt,
                    )
                    for band in bands
                ],
            )
        conn.commit()
    return len(bands)
