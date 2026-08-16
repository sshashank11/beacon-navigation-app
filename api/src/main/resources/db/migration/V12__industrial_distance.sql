ALTER TABLE segment_industrial_sample
    ADD COLUMN nearest_facility_m REAL CHECK (nearest_facility_m >= 0);
