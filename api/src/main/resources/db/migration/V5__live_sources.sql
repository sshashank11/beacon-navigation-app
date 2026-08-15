CREATE TABLE nws_alert (
  id TEXT PRIMARY KEY,
  event TEXT NOT NULL,
  headline TEXT,
  severity TEXT,
  urgency TEXT,
  certainty TEXT,
  onset TIMESTAMPTZ,
  expires_at TIMESTAMPTZ,
  description TEXT,
  instruction TEXT,
  updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX nws_alert_expires_at_idx ON nws_alert (expires_at);

CREATE TABLE construction_permit (
  permit_id TEXT PRIMARY KEY,
  source TEXT NOT NULL,
  bin TEXT NOT NULL,
  permit_type TEXT NOT NULL,
  issued_at DATE,
  expires_at DATE NOT NULL,
  severity SMALLINT NOT NULL CHECK (severity BETWEEN 1 AND 4),
  geom GEOMETRY(Point, 4326) NOT NULL
);

CREATE INDEX construction_permit_expires_at_idx
  ON construction_permit (expires_at);

CREATE INDEX construction_permit_geom_idx
  ON construction_permit USING GIST (geom);
