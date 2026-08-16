CREATE TABLE segment (
    id BIGSERIAL PRIMARY KEY,
    osm_way_id BIGINT NOT NULL,
    seq INTEGER NOT NULL CHECK (seq >= 0),
    geom geometry(LineString, 4326) NOT NULL,
    length_m REAL NOT NULL,
    grade_pct REAL,
    highway_class TEXT,
    has_sidewalk BOOLEAN,
    CONSTRAINT segment_osm_way_seq_unique UNIQUE (osm_way_id, seq),
    CONSTRAINT segment_has_line CHECK (ST_NPoints(geom) >= 2),
    CONSTRAINT segment_positive_length CHECK (length_m > 0)
);

CREATE INDEX segment_geom_gix ON segment USING GIST (geom);
CREATE INDEX segment_osm_way_id_idx ON segment (osm_way_id);

CREATE FUNCTION set_segment_length_m()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.length_m := ST_Length(NEW.geom::geography);
    RETURN NEW;
END;
$$;

CREATE TRIGGER segment_length_m_trigger
BEFORE INSERT OR UPDATE OF geom ON segment
FOR EACH ROW
EXECUTE FUNCTION set_segment_length_m();
