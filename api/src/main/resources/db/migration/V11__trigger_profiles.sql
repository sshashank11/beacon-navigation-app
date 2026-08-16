CREATE TABLE trigger_profile (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    label TEXT,
    mode TEXT NOT NULL,
    weights JSONB NOT NULL DEFAULT '{}'::jsonb,
    hard_avoids TEXT[] NOT NULL DEFAULT '{}',
    max_grade_pct REAL NOT NULL DEFAULT 20.0
        CHECK (max_grade_pct BETWEEN 0.0 AND 20.0),
    detour_tolerance REAL NOT NULL DEFAULT 0.25
        CHECK (detour_tolerance BETWEEN 0.0 AND 2.0),
    conservatism REAL NOT NULL DEFAULT 1.0
        CHECK (conservatism > 0.0 AND conservatism <= 2.0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (jsonb_typeof(weights) = 'object')
);

CREATE INDEX trigger_profile_user_id_idx ON trigger_profile (user_id);
