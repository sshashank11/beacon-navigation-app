package com.beacon.api.conditions;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CitywideReadingRepository extends JpaRepository<CitywideReading, CitywideReadingId> {

    @Query(value = """
            SELECT DISTINCT ON (hazard, station_id)
              hazard, station_id, observed_at, source, value, unit
            FROM citywide_reading
            WHERE observed_at >= now() - (:lookbackHours * interval '1 hour')
            ORDER BY hazard, station_id, observed_at DESC
            """, nativeQuery = true)
    List<CitywideReading> findLatestPerStation(int lookbackHours);
}
