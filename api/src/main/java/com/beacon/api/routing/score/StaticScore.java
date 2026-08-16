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
    POLLEN("clw_pollen"),
    GRADE("clw_grade"),
    INDUSTRIAL_WITHIN_200M("clw_industrial_within_200m"),
    /** Percentile of open sky overhead; a low value is a street canyon. */
    SKY_VIEW("clw_svf"),
    /** Percentile of vehicle and pedestrian pixel density from street imagery. */
    CROWD("clw_crowd");

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
