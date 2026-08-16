CREATE TABLE street_image (
    mapillary_id TEXT PRIMARY KEY,
    geom GEOMETRY(Point, 4326) NOT NULL,
    compass_angle REAL NOT NULL CHECK (compass_angle >= 0 AND compass_angle < 360),
    captured_at TIMESTAMPTZ NOT NULL,
    thumb_url TEXT NOT NULL CHECK (thumb_url <> ''),
    nearest_segment_id BIGINT REFERENCES segment(id) ON DELETE SET NULL,
    harvested_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX street_image_geom_gix ON street_image USING GIST (geom);
CREATE INDEX street_image_nearest_segment_id_idx
    ON street_image (nearest_segment_id)
    WHERE nearest_segment_id IS NOT NULL;
CREATE INDEX street_image_captured_at_idx ON street_image (captured_at DESC);

CREATE TABLE image_analysis (
    mapillary_id TEXT NOT NULL REFERENCES street_image(mapillary_id) ON DELETE CASCADE,
    model_version TEXT NOT NULL CHECK (model_version <> ''),
    vegetation_frac REAL NOT NULL CHECK (vegetation_frac BETWEEN 0 AND 1),
    sky_frac REAL NOT NULL CHECK (sky_frac BETWEEN 0 AND 1),
    road_frac REAL NOT NULL CHECK (road_frac BETWEEN 0 AND 1),
    sidewalk_frac REAL NOT NULL CHECK (sidewalk_frac BETWEEN 0 AND 1),
    sky_view_factor REAL NOT NULL CHECK (sky_view_factor BETWEEN 0 AND 1),
    vehicle_count INTEGER NOT NULL CHECK (vehicle_count >= 0),
    person_count INTEGER NOT NULL CHECK (person_count >= 0),
    construction_present BOOLEAN NOT NULL,
    construction_conf REAL NOT NULL CHECK (construction_conf BETWEEN 0 AND 1),
    raw_class_hist JSONB NOT NULL DEFAULT '{}'::jsonb,
    analyzed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (mapillary_id, model_version),
    CHECK (jsonb_typeof(raw_class_hist) = 'object')
);

CREATE INDEX image_analysis_model_version_idx ON image_analysis (model_version);
