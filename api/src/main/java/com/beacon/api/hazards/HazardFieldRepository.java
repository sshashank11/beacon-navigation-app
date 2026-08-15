package com.beacon.api.hazards;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface HazardFieldRepository extends JpaRepository<HazardField, Long> {

    @Query(value = """
            SELECT
              hf.id,
              hf.hazard,
              hf.observed_at AS "observedAt",
              hf.band_min AS "bandMin",
              hf.band_max AS "bandMax",
              hf.severity,
              ST_AsText(hf.geom) AS "geometryWkt"
            FROM hazard_field hf
            JOIN (
              SELECT hazard, max(observed_at) AS observed_at
              FROM hazard_field
              WHERE observed_at >= now() - interval '2 hours'
              GROUP BY hazard
            ) latest
              ON latest.hazard = hf.hazard
             AND latest.observed_at = hf.observed_at
            ORDER BY hf.hazard, hf.severity
            """, nativeQuery = true)
    List<HazardFieldRow> findLatestFieldRows();
}
