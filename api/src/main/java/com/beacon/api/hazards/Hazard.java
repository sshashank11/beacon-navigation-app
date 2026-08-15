package com.beacon.api.hazards;

import java.util.Locale;

public enum Hazard {
    PM25("pm25"),
    OZONE("ozone"),
    NO2("no2"),
    POLLEN_TREE("pollen_tree"),
    POLLEN_GRASS("pollen_grass"),
    POLLEN_WEED("pollen_weed"),
    TRAFFIC_PROX("traffic_prox"),
    CONSTRUCTION("construction"),
    INDUSTRIAL_PROX("industrial_prox"),
    GRADE("grade"),
    HEAT("heat"),
    COLD_AIR("cold_air"),
    HUMIDITY("humidity"),
    CROWD_DENSITY("crowd_density"),
    SHADE_DEFICIT("shade_deficit");

    private final String key;

    Hazard(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static Hazard fromKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        for (Hazard hazard : values()) {
            if (hazard.key.equals(normalized)) {
                return hazard;
            }
        }
        throw new IllegalArgumentException("Unknown hazard: " + key);
    }
}
