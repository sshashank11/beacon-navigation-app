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

The monorepo scaffolds are ready. The live environmental slice includes scheduled air quality, pollen, weather alert, and construction ingestion; PostGIS hazard fields; Redis-cached GraphHopper areas; and the conditions API. The base routing and trigger-profile phases still need to be connected before live areas can affect an end-to-end route.

## Local development

Prerequisites: Java 21 and Docker Desktop.

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

The API uses the root environment variables when present and otherwise connects to the local Beacon PostGIS database defined in `docker-compose.yml`. Once the stack and API are running, the health check is available at `http://localhost:8080/actuator/health`.
