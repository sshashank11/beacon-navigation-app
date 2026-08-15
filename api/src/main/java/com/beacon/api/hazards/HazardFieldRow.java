package com.beacon.api.hazards;

import java.time.Instant;

public interface HazardFieldRow {
    Long getId();

    String getHazard();

    Instant getObservedAt();

    Float getBandMin();

    Float getBandMax();

    Short getSeverity();

    String getGeometryWkt();
}
