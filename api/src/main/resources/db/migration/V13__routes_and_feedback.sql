CREATE TABLE route (
    id UUID PRIMARY KEY,
    user_id UUID,
    profile_id UUID REFERENCES trigger_profile(id) ON DELETE CASCADE,
    variant TEXT NOT NULL CHECK (variant IN ('fastest', 'balanced', 'cleanest')),
    geom GEOMETRY(LineString, 4326) NOT NULL,
    distance_m REAL NOT NULL CHECK (distance_m >= 0),
    duration_s REAL NOT NULL CHECK (duration_s >= 0),
    exposure_breakdown JSONB NOT NULL DEFAULT '{}'::jsonb,
    instructions JSONB NOT NULL DEFAULT '[]'::jsonb,
    computed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX route_profile_id_idx ON route (profile_id);
CREATE INDEX route_computed_at_idx ON route (computed_at DESC);
CREATE INDEX route_geom_idx ON route USING GIST (geom);

CREATE TABLE route_feedback (
    id UUID PRIMARY KEY,
    route_id UUID NOT NULL REFERENCES route(id) ON DELETE CASCADE,
    felt_worse BOOLEAN NOT NULL,
    which_segments JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (jsonb_typeof(which_segments) = 'array')
);

CREATE INDEX route_feedback_route_id_idx ON route_feedback (route_id);
