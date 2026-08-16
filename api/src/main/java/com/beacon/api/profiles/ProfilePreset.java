package com.beacon.api.profiles;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum ProfilePreset {
    ASTHMA,
    ALLERGIES,
    COPD,
    CHEMICAL_SENSITIVITY,
    CARDIAC,
    NONE;

    @JsonValue
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static ProfilePreset fromJson(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
