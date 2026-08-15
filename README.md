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

Phase 0 scaffolding is in progress.
