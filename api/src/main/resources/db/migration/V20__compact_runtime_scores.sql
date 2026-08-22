ALTER TABLE segment_static_score
    ADD COLUMN industrial_within_200m BOOLEAN;

UPDATE segment_static_score AS score
SET industrial_within_200m = sample.nearest_facility_m <= 200
FROM segment_industrial_sample AS sample
WHERE sample.segment_id = score.segment_id;

UPDATE segment_static_score
SET industrial_within_200m = false
WHERE industrial_within_200m IS NULL;

ALTER TABLE segment_static_score
    ALTER COLUMN industrial_within_200m SET DEFAULT false,
    ALTER COLUMN industrial_within_200m SET NOT NULL;

-- The unique (osm_way_id, seq) index already supports lookups by osm_way_id.
DROP INDEX segment_osm_way_id_idx;
