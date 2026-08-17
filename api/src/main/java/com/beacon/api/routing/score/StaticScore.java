package com.beacon.api.routing.score;

import java.util.Arrays;
import java.util.Optional;

public enum StaticScore {
    PM25("clw_pm25", false),
    NO2("clw_no2", false),
    OZONE("clw_ozone", false),
    TRAFFIC("clw_traffic", false),
    INDUSTRIAL("clw_industrial", false),
    SHADE("clw_shade", false),
    POLLEN("clw_pollen", false),
    GRADE("clw_grade", false),
    INDUSTRIAL_WITHIN_200M("clw_industrial_within_200m", false),
    /** Percentile of open sky overhead; a low value is a street canyon. */
    SKY_VIEW("clw_svf", true),
    /** Percentile of vehicle and pedestrian pixel density from street imagery. */
    CROWD("clw_crowd", true);

    /**
     * Written where a score has no measurement, and distinct from every real
     * percentile.
     *
     * <p>Imagery covers a demo corridor, not the city: at the time of writing,
     * 944 of 580,211 segments. Defaulting an absent sky view factor to zero
     * would make it indistinguishable from the lowest percentile, so every
     * unphotographed street in NYC would read as a severe canyon and get
     * penalised. The sentinel sits above the 0-100 percentile range and inside
     * the 7 bits, so rules can exclude it explicitly.
     */
    public static final int NO_DATA = 127;

    private final String encodedValueName;
    private final boolean optional;

    StaticScore(String encodedValueName, boolean optional) {
        this.encodedValueName = encodedValueName;
        this.optional = optional;
    }

    public String encodedValueName() {
        return encodedValueName;
    }

    /** True when a segment may legitimately have no measurement at all. */
    public boolean optional() {
        return optional;
    }

    /** Guards a rule so it never fires on a segment with no measurement. */
    public String presentCondition() {
        return encodedValueName + " < " + NO_DATA;
    }

    public static Optional<StaticScore> fromEncodedValueName(String name) {
        return Arrays.stream(values())
                .filter(score -> score.encodedValueName.equals(name))
                .findFirst();
    }
}
