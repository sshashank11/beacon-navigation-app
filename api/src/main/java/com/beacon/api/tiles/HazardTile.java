package com.beacon.api.tiles;

import java.util.Arrays;

public enum HazardTile {
    PM25("pm25", "pm25_prior"),
    NO2("no2", "no2_prior"),
    OZONE("ozone", "ozone_prior"),
    TRAFFIC("traffic", "traffic_prox"),
    INDUSTRIAL("industrial", "industrial_prox"),
    SHADE("shade", "shade_benefit"),
    POLLEN("pollen", "pollen_source");

    private final String slug;
    private final String scoreColumn;

    HazardTile(String slug, String scoreColumn) {
        this.slug = slug;
        this.scoreColumn = scoreColumn;
    }

    public String slug() {
        return slug;
    }

    String scoreColumn() {
        return scoreColumn;
    }

    public static HazardTile fromSlug(String value) {
        return Arrays.stream(values())
                .filter(hazard -> hazard.slug.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported hazard tile: " + value));
    }
}
