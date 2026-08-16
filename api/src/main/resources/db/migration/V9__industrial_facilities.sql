CREATE TABLE industrial_facility (
    source TEXT NOT NULL,
    source_id TEXT NOT NULL,
    name TEXT NOT NULL,
    programs TEXT[] NOT NULL DEFAULT '{}',
    state CHAR(2) NOT NULL,
    geom GEOMETRY(Point, 4326) NOT NULL,
    geom_32618 GEOMETRY(Point, 32618)
        GENERATED ALWAYS AS (ST_Transform(geom, 32618)) STORED,
    PRIMARY KEY (source, source_id)
);

CREATE INDEX industrial_facility_geom_gix
    ON industrial_facility USING GIST (geom);
CREATE INDEX industrial_facility_geom_32618_gix
    ON industrial_facility USING GIST (geom_32618);

CREATE TABLE segment_industrial_sample (
    segment_id BIGINT PRIMARY KEY REFERENCES segment(id) ON DELETE CASCADE,
    facility_count INTEGER NOT NULL CHECK (facility_count >= 0),
    raw_kernel REAL NOT NULL CHECK (raw_kernel >= 0),
    computed_at TIMESTAMPTZ NOT NULL
);
