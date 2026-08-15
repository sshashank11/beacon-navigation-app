package com.beacon.api.conditions;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface NwsAlertRepository extends JpaRepository<NwsAlert, String> {

    @Query("""
            SELECT alert
            FROM NwsAlert alert
            WHERE alert.expiresAt IS NULL OR alert.expiresAt >= CURRENT_TIMESTAMP
            ORDER BY alert.onset DESC, alert.event
            """)
    List<NwsAlert> findActive();
}
