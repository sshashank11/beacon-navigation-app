CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE citywide_reading (
  hazard TEXT NOT NULL,
  station_id TEXT NOT NULL,
  observed_at TIMESTAMPTZ NOT NULL,
  source TEXT NOT NULL,
  value REAL NOT NULL,
  unit TEXT NOT NULL,
  PRIMARY KEY (hazard, station_id, observed_at)
);

CREATE INDEX citywide_reading_hazard_observed_at_idx
  ON citywide_reading (hazard, observed_at DESC);

CREATE TABLE hazard_field (
  id BIGSERIAL PRIMARY KEY,
  hazard TEXT NOT NULL,
  observed_at TIMESTAMPTZ NOT NULL,
  band_min REAL NOT NULL,
  band_max REAL NOT NULL,
  severity SMALLINT NOT NULL CHECK (severity BETWEEN 0 AND 4),
  geom GEOMETRY(MultiPolygon, 4326) NOT NULL
);

CREATE INDEX hazard_field_hazard_observed_at_idx
  ON hazard_field (hazard, observed_at DESC);

CREATE INDEX hazard_field_geom_idx
  ON hazard_field USING GIST (geom);
