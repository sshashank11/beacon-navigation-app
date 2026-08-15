package com.beacon.api.conditions;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Embeddable
public class CitywideReadingId implements Serializable {

    @Column(nullable = false)
    private String hazard;

    @Column(name = "station_id", nullable = false)
    private String stationId;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    protected CitywideReadingId() {
    }

    public CitywideReadingId(String hazard, String stationId, Instant observedAt) {
        this.hazard = hazard;
        this.stationId = stationId;
        this.observedAt = observedAt;
    }

    public String getHazard() {
        return hazard;
    }

    public String getStationId() {
        return stationId;
    }

    public Instant getObservedAt() {
        return observedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CitywideReadingId that)) {
            return false;
        }
        return Objects.equals(hazard, that.hazard)
                && Objects.equals(stationId, that.stationId)
                && Objects.equals(observedAt, that.observedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(hazard, stationId, observedAt);
    }
}
