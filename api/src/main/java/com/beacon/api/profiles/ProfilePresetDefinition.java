package com.beacon.api.profiles;

import com.beacon.api.hazards.Hazard;
import java.util.Map;
import java.util.Set;

public record ProfilePresetDefinition(
        Map<Hazard, Double> weights,
        Set<HardAvoid> hardAvoids,
        double maxGradePct) {

    public ProfilePresetDefinition {
        weights = Map.copyOf(weights);
        hardAvoids = Set.copyOf(hardAvoids);
    }
}
