package com.beacon.api.hazards;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.locationtech.jts.geom.MultiPolygon;

@Entity
@Table(name = "hazard_field")
public class HazardField {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String hazard;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    @Column(name = "band_min", nullable = false)
    private Float bandMin;

    @Column(name = "band_max", nullable = false)
    private Float bandMax;

    @Column(nullable = false)
    private Short severity;

    @Column(nullable = false, columnDefinition = "geometry(MultiPolygon,4326)")
    private MultiPolygon geom;

    protected HazardField() {
    }

    public Long getId() {
        return id;
    }

    public String getHazard() {
        return hazard;
    }

    public Instant getObservedAt() {
        return observedAt;
    }

    public Float getBandMin() {
        return bandMin;
    }

    public Float getBandMax() {
        return bandMax;
    }

    public Short getSeverity() {
        return severity;
    }

    public MultiPolygon getGeom() {
        return geom;
    }
}
