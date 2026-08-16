package com.beacon.api.profiles;

import java.util.Arrays;

public enum HardAvoid {
    ACTIVE_CONSTRUCTION_FRONTAGE("active_construction_frontage"),
    INDUSTRIAL_WITHIN_200M("industrial_within_200m"),
    GRADE_ABOVE_FIVE_PERCENT("grade_above_5_pct"),
    GRADE_ABOVE_SIX_PERCENT("grade_above_6_pct");

    private final String key;

    HardAvoid(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static HardAvoid fromKey(String key) {
        return Arrays.stream(values())
                .filter(avoid -> avoid.key.equalsIgnoreCase(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown hard avoid: " + key));
    }
}
