package com.beacon.api.hazards;

import java.time.Instant;

public record HazardArea(
        long id,
        String name,
        String hazard,
        short severity,
        float bandMin,
        float bandMax,
        Instant observedAt
) {
}
