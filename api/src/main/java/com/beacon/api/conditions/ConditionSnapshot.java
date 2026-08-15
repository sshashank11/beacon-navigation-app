package com.beacon.api.conditions;

import java.time.Instant;
import java.util.List;

public record ConditionSnapshot(
        Instant generatedAt,
        List<HazardCondition> hazards,
        List<AirQualityCondition> airQuality,
        PollenCondition pollen,
        WeatherCondition weather,
        List<AlertCondition> alerts,
        String summary
) {
}
