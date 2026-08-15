package com.beacon.api.conditions;

import java.util.Set;

public record SeasonalGates(
        boolean shadeActive,
        Set<String> activePollenHazards
) {
}
