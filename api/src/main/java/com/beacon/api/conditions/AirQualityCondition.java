package com.beacon.api.conditions;

import java.time.Instant;

public record AirQualityCondition(
        String pollutant,
        int aqi,
        String category,
        Instant observedAt
) {
}
