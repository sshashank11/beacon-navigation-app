# Beacon Pipeline

Environmental data ingestion, geospatial ETL, and hazard scoring jobs for Beacon.

## Development

```bash
uv sync
uv run python -m beacon_pipeline --help
```

Start the local Prefect API and the scheduled runner in separate terminals:

```bash
uv run prefect server start
uv run beacon-pipeline serve
```

The runner polls OpenAQ every 15 minutes, AirNow and NWS hourly, Google Pollen
daily at 04:00 America/New_York, DOB permits nightly, and hazard fields every
15 minutes. Pollen requests reserve a Redis-backed daily quota before each
billed API call.

Place georeferenced `pm25.tif`, `no2.tif`, and `ozone.tif` NYCCAS priors in
`NYCCAS_RASTER_DIR`. Each raster must use the same concentration unit as its
OpenAQ readings. The field builder scales the prior by the current citywide
mean, contours four fixed baseline severity bands, simplifies them at 50 m,
and writes no more than 40 GraphHopper areas.

Run the deterministic parser and raster tests with:

```bash
uv run --with pytest pytest
```

## Routing data

Prepare the five-borough OpenStreetMap extract from the repository root:

```bash
make osm
```

The command downloads the current New York State PBF from Geofabrik, verifies
its published checksum, downloads the five shoreline-clipped borough polygons
from NYC Open Data, dissolves them into one boundary, and writes
`data/osm/nyc.osm.pbf`. It uses a local `osmium` executable when available and
otherwise runs the pinned `docker.io/iboates/osmium:1.19.0` image. Source and
generated map data are gitignored. Use `--force-download` only when the source
PBF needs to be fetched again despite a matching checksum.

## Segment backbone

Start Postgres and boot the API once so Flyway creates the `segment` table:

```bash
make up
make api
```

Then stream accessible walking and cycling ways from the clipped PBF into
PostGIS:

```bash
cd pipeline
uv run beacon-pipeline extract-segments \
  --osm-path ../data/osm/nyc.osm.pbf
```

Osmium emits GeoJSON sequence records without loading the complete network
into Python memory. Ways are filtered by access tags, divided into chunks no
longer than 100 m, and loaded with Psycopg `COPY`. The database computes
`length_m` with `ST_Length(geom::geography)` and maintains a GIST geometry
index. Replacement is transactional, so the previous segment set remains if
an import fails.

The reference NYC import from August 15, 2026 produced 580,211 valid segments
from 394,368 OSM ways. Median length was 52.33 m, maximum length was 99.25 m,
and no geometries fell outside the configured NYC bounds.

## Elevation grades

Download and sample the two NYC-covering USGS 3DEP rasters with:

```bash
uv run beacon-pipeline enrich-elevation \
  --dem-dir ../data/elevation
```

The command downloads the current `n41w074` and `n41w075` 1/3-arc-second
GeoTIFFs from the public [USGS 3DEP collection](https://data.usgs.gov/datacatalog/data/USGS%3A3a81321b-c153-416f-98b7-cc8e5f0e17c3).
Rasterio samples each segment endpoint, computes signed grade, and clamps DEM
noise to +/-20%. The reference run graded all 580,211 segments; 0.473% reached
the clamp, with the 5th and 95th percentiles at -3.60% and 3.56%.

## NYCCAS pollution priors

Download the current NYC Community Air Survey archive, select its newest model
year, and refresh the segment pollution percentiles with:

```bash
uv run beacon-pipeline refresh-nyccas-scores \
  --raster-dir ../data/nyccas
```

The job reads the official NYC Open Data attachment metadata instead of pinning
an attachment URL. It extracts the newest annual PM2.5 and NO2 grids and summer
ozone grid, reprojects each source from NAD83 New York Long Island State Plane
feet to EPSG:4326, and samples one or three points per segment. Raw values remain
in `segment_nyccas_sample`; comparable 0-100 percentile ranks are written to
`segment_static_score`.
