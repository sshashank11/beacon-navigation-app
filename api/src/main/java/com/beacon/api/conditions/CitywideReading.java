package com.beacon.api.conditions;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "citywide_reading")
public class CitywideReading {

    @EmbeddedId
    private CitywideReadingId id;

    @Column(nullable = false)
    private String source;

    @Column(nullable = false)
    private Float value;

    @Column(nullable = false)
    private String unit;

    protected CitywideReading() {
    }

    public CitywideReading(String hazard, String stationId, Instant observedAt, String source, Float value, String unit) {
        this.id = new CitywideReadingId(hazard, stationId, observedAt);
        this.source = source;
        this.value = value;
        this.unit = unit;
    }

    public CitywideReadingId getId() {
        return id;
    }

    public String getSource() {
        return source;
    }

    public Float getValue() {
        return value;
    }

    public String getUnit() {
        return unit;
    }
}
