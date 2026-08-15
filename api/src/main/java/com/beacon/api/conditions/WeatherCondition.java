package com.beacon.api.conditions;

public record WeatherCondition(
        Double temperatureC,
        Double humidityPercent,
        Double windSpeedMph,
        Double windBearingDegrees
) {
}
