CREATE TABLE segment_static_score (
    segment_id BIGINT PRIMARY KEY REFERENCES segment(id) ON DELETE CASCADE,
    pm25_prior REAL CHECK (pm25_prior BETWEEN 0 AND 100),
    no2_prior REAL CHECK (no2_prior BETWEEN 0 AND 100),
    ozone_prior REAL CHECK (ozone_prior BETWEEN 0 AND 100),
    traffic_prox REAL CHECK (traffic_prox BETWEEN 0 AND 100),
    industrial_prox REAL CHECK (industrial_prox BETWEEN 0 AND 100),
    shade_benefit REAL CHECK (shade_benefit BETWEEN 0 AND 100),
    pollen_source REAL CHECK (pollen_source BETWEEN 0 AND 100),
    sky_view_factor REAL CHECK (sky_view_factor BETWEEN 0 AND 100),
    crowd_prior REAL CHECK (crowd_prior BETWEEN 0 AND 100),
    computed_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE segment_nyccas_sample (
    segment_id BIGINT PRIMARY KEY REFERENCES segment(id) ON DELETE CASCADE,
    pm25_raw REAL,
    no2_raw REAL,
    ozone_raw REAL,
    sampled_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX segment_nyccas_sample_sampled_at_idx
    ON segment_nyccas_sample (sampled_at DESC);
