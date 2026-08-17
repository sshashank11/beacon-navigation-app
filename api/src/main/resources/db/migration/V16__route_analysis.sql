-- On-demand street imagery analysis for a saved route.
-- Frames are sampled along the route at fixed intervals; scoring happens in
-- the Python worker, so a route can be sampled long before its frames exist
-- in image_analysis.
CREATE TABLE route_analysis (
    id UUID PRIMARY KEY,
    route_id UUID NOT NULL REFERENCES route(id) ON DELETE CASCADE,
    status TEXT NOT NULL CHECK (status IN ('pending', 'ready', 'no_imagery')),
    frame_count INTEGER NOT NULL DEFAULT 0 CHECK (frame_count >= 0),
    model_version TEXT NOT NULL CHECK (model_version <> ''),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX route_analysis_route_id_idx ON route_analysis (route_id);
CREATE INDEX route_analysis_requested_at_idx ON route_analysis (requested_at DESC);

CREATE TABLE route_analysis_frame (
    analysis_id UUID NOT NULL REFERENCES route_analysis(id) ON DELETE CASCADE,
    seq INTEGER NOT NULL CHECK (seq >= 0),
    distance_offset_m REAL NOT NULL CHECK (distance_offset_m >= 0),
    mapillary_id TEXT NOT NULL REFERENCES street_image(mapillary_id) ON DELETE CASCADE,
    route_bearing_deg REAL NOT NULL,
    image_distance_m REAL NOT NULL CHECK (image_distance_m >= 0),
    PRIMARY KEY (analysis_id, seq)
);

CREATE INDEX route_analysis_frame_mapillary_id_idx
    ON route_analysis_frame (mapillary_id);
