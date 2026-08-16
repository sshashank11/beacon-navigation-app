package com.beacon.api.conditions;

import java.util.Set;

public record SeasonalGates(
        boolean shadeActive,
        boolean treePollenSeasonActive,
        Set<String> activePollenHazards
) {
}
