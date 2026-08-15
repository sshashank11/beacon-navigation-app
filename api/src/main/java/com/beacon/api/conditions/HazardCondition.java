package com.beacon.api.conditions;

import java.time.Instant;

public record HazardCondition(
        String hazard,
        double meanValue,
        String unit,
        int stationCount,
        Instant latestObservedAt,
        String source
) {
}
