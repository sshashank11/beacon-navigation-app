package com.beacon.api.routing;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum RouteMode {
    FOOT("foot"),
    BIKE("bike");

    private final String profile;

    RouteMode(String profile) {
        this.profile = profile;
    }

    public String profile() {
        return profile;
    }

    @JsonCreator
    public static RouteMode fromJson(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.toLowerCase()) {
            case "foot", "walk", "walking" -> FOOT;
            case "bike", "bicycle", "cycling" -> BIKE;
            default -> throw new IllegalArgumentException("Unsupported route mode: " + value);
        };
    }
}
