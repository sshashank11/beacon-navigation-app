package com.beacon.api.routing.score;

import java.util.Arrays;
import java.util.Optional;

public enum StaticScore {
    PM25("clw_pm25"),
    NO2("clw_no2"),
    OZONE("clw_ozone"),
    TRAFFIC("clw_traffic"),
    INDUSTRIAL("clw_industrial"),
    SHADE("clw_shade"),
    POLLEN("clw_pollen");

    private final String encodedValueName;

    StaticScore(String encodedValueName) {
        this.encodedValueName = encodedValueName;
    }

    public String encodedValueName() {
        return encodedValueName;
    }

    public static Optional<StaticScore> fromEncodedValueName(String name) {
        return Arrays.stream(values())
                .filter(score -> score.encodedValueName.equals(name))
                .findFirst();
    }
}
