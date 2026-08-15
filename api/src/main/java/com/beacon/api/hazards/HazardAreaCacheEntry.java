package com.beacon.api.hazards;

import java.io.Serializable;
import java.time.Instant;

public record HazardAreaCacheEntry(
        long id,
        String hazard,
        short severity,
        float bandMin,
        float bandMax,
        Instant observedAt,
        String geometryWkt
) implements Serializable {
}
