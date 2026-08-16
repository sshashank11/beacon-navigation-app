CREATE TABLE traffic_road (
    osm_way_id BIGINT PRIMARY KEY,
    highway_class TEXT NOT NULL,
    proxy_weight REAL NOT NULL CHECK (proxy_weight > 0),
    geom GEOMETRY(LineString, 4326) NOT NULL,
    geom_32618 GEOMETRY(LineString, 32618)
        GENERATED ALWAYS AS (ST_Transform(geom, 32618)) STORED
);

CREATE INDEX traffic_road_geom_gix ON traffic_road USING GIST (geom);
CREATE INDEX traffic_road_geom_32618_gix ON traffic_road USING GIST (geom_32618);
CREATE INDEX traffic_road_highway_class_idx ON traffic_road (highway_class);

CREATE TABLE segment_traffic_sample (
    segment_id BIGINT PRIMARY KEY REFERENCES segment(id) ON DELETE CASCADE,
    road_class_count SMALLINT NOT NULL CHECK (road_class_count >= 0),
    nearest_road_m REAL CHECK (nearest_road_m >= 0),
    raw_kernel REAL NOT NULL CHECK (raw_kernel >= 0),
    computed_at TIMESTAMPTZ NOT NULL
);
