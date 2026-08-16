package com.beacon.api.conditions;

import java.time.Instant;
import java.time.Month;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Set;

final class TreePollenSeason {

    private static final Set<Month> ACTIVE_MONTHS = EnumSet.range(Month.FEBRUARY, Month.JUNE);

    private TreePollenSeason() {
    }

    static boolean isActive(Instant instant) {
        Month month = instant.atZone(ZoneOffset.UTC).getMonth();
        return ACTIVE_MONTHS.contains(month);
    }
}
