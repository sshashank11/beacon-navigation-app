-- Recency-decayed medians of per-image CV metrics, aggregated per segment.
-- Raw values land here; segment_static_score keeps the percentile ranks.
CREATE TABLE segment_image_sample (
    segment_id BIGINT PRIMARY KEY REFERENCES segment(id) ON DELETE CASCADE,
    sky_view_factor_raw REAL NOT NULL CHECK (sky_view_factor_raw BETWEEN 0 AND 1),
    crowd_density_raw REAL NOT NULL CHECK (crowd_density_raw BETWEEN 0 AND 1),
    frame_count INTEGER NOT NULL CHECK (frame_count > 0),
    model_version TEXT NOT NULL CHECK (model_version <> ''),
    sampled_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX segment_image_sample_sampled_at_idx
    ON segment_image_sample (sampled_at DESC);
