package com.beacon.api.routing;

import static com.graphhopper.json.Statement.If;
import static com.graphhopper.json.Statement.Op.MULTIPLY;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.graphhopper.GHRequest;
import com.graphhopper.util.CustomModel;

public enum RouteVariant {
    FASTEST,
    CLEANEST;

    private static final String HIGH_PM25_CONDITION = "clw_pm25 > 70";
    private static final String HIGH_PM25_MULTIPLIER = "0.4";

    public void configure(GHRequest request) {
        if (this == CLEANEST) {
            request.setCustomModel(pm25Model());
        }
    }

    static CustomModel pm25Model() {
        return new CustomModel().addToPriority(If(
                HIGH_PM25_CONDITION,
                MULTIPLY,
                HIGH_PM25_MULTIPLIER));
    }

    @JsonCreator
    public static RouteVariant fromJson(String value) {
        if (value == null || value.isBlank()) {
            return FASTEST;
        }
        return switch (value.toLowerCase()) {
            case "fastest", "shortest" -> FASTEST;
            case "cleanest", "lower_pm25", "low_pm25" -> CLEANEST;
            default -> throw new IllegalArgumentException("Unsupported route variant: " + value);
        };
    }
}
