# Beacon Navigation App

Beacon is a health-aware navigation app for NYC pedestrians and cyclists. It aims to compare walking and biking routes by distance, duration, and relative exposure to environmental triggers such as air pollution, pollen, construction, traffic exhaust, grade, heat, humidity, and crowding.

## Architecture

Beacon is planned as a monorepo with three application surfaces:

```text
beacon-navigation-app/
  api/       Spring Boot API, GraphHopper routing, auth, profiles, routes
  pipeline/  Python ingestion, geospatial ETL, hazard scoring, CV jobs
  web/       React + TypeScript frontend with MapLibre
```

Supporting services run locally through Docker Compose:

```text
Postgres + PostGIS  Spatial database for routes, segments, scores, and readings
Redis               Hot cache and async job queue
MinIO               Local object storage for generated audio and artifacts
```

The routing architecture separates data by refresh cadence:

```text
Static/monthly   OSM graph, NYCCAS priors, trees, elevation, EPA sites
Slow/nightly     Construction permits and streetscape scores
Fast/hourly      Live AQI, pollen, weather, wind, alerts
```

Static and slow data become segment-level scores baked into the routing graph. Fast data is injected per request as live hazard areas, so Beacon can respond to changing conditions without rebuilding the graph.

## Status

Beacon currently provides a branded React and MapLibre route planner for
choosing an origin, destination, and walking or cycling mode. The Spring Boot
API imports the five-borough OpenStreetMap network into embedded GraphHopper
and returns route geometry, distance, duration, and turn instructions.

The PostGIS pipeline maintains 580,211 routable segments from 394,368 OSM ways
with SQL-computed lengths and USGS 3DEP elevation grades. It now calculates
0-100 percentile scores for NYCCAS PM2.5, NO2, and ozone; street-tree shade and
pollen; EPA industrial-facility proximity; and OSM high-class-road traffic
proximity. GraphHopper imports length-weighted way averages as seven 7-bit
encoded values. The reference graph contains 1,132,204 edges, of which
1,074,495 have at least one nonzero static score.

Scheduled air quality, pollen, weather alert, and construction ingestion,
PostGIS hazard fields, Redis-cached GraphHopper areas, and the conditions API
are also implemented. The next roadmap work begins at step 41: apply a PM2.5
penalty with a GraphHopper `CustomModel` and prove that the health-aware route
diverges from the shortest route before adding the map hazard overlay.

## Local development

Prerequisites: Java 21, Python 3.12, `uv`, and Docker Desktop.

```bash
make up
make osm
make api
make pipeline
make web
```

`make osm` downloads roughly 500 MB of source map data and creates the smaller
five-borough extract at `data/osm/nyc.osm.pbf`. Docker is used for Osmium when
the command is not installed locally.

The API uses the root environment variables when present and otherwise connects
to the local Beacon PostGIS database defined in `docker-compose.yml`. Once the
stack and API are running, the health check is available at
`http://localhost:8080/actuator/health`, and the web app is available at
`http://localhost:5173`.

## Static score build

After the segment and elevation jobs documented in `pipeline/README.md`, run
the current static scoring jobs from `pipeline/`:

```bash
uv run beacon-pipeline refresh-nyccas-scores --raster-dir ../data/nyccas
uv run beacon-pipeline refresh-street-tree-scores
uv run beacon-pipeline refresh-industrial-scores --epa-data-dir ../data/epa
uv run beacon-pipeline refresh-traffic-scores --osm-path ../data/osm/nyc.osm.pbf
```

The industrial job downloads public EPA TRI and ECHO data. The traffic job
uses motorway, trunk, primary, and secondary OSM roads with class-weighted
distance decay. Both jobs preserve their raw proximity measurements and write
comparable percentile ranks to `segment_static_score`.

Static scores are written during GraphHopper import. Stop the API and remove
`graph-cache/` after refreshing score data or changing encoded-value definitions,
then start the API again to force a complete graph rebuild. Startup logs report
the indexed OSM way count and number of graph edges carrying nonzero scores.

Run the backend and pipeline test suites with:

```bash
cd api && ./gradlew test
cd ../pipeline && uv run --with pytest pytest
```

## Route API

`POST /api/v1/routes` accepts origin and destination arrays in
`[latitude, longitude]` order. GeoJSON coordinates in the response follow the
standard `[longitude, latitude]` order.

```bash
curl -X POST http://localhost:8080/api/v1/routes \
  -H "Content-Type: application/json" \
  -d '{
    "origin": [40.7484, -73.9857],
    "destination": [40.7359, -73.9911],
    "mode": "foot"
  }'
```

Supported modes are `foot` and `bike`; common aliases such as `walk` and
`cycling` are also accepted. The response contains a GeoJSON LineString,
`distance_m`, `duration_s`, and GraphHopper instruction details.
