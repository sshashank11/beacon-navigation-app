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
uv run python -m unittest discover -s tests -v
```
