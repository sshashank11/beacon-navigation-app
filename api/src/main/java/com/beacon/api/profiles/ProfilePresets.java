package com.beacon.api.profiles;

import static com.beacon.api.hazards.Hazard.COLD_AIR;
import static com.beacon.api.hazards.Hazard.CONSTRUCTION;
import static com.beacon.api.hazards.Hazard.GRADE;
import static com.beacon.api.hazards.Hazard.HEAT;
import static com.beacon.api.hazards.Hazard.HUMIDITY;
import static com.beacon.api.hazards.Hazard.INDUSTRIAL_PROX;
import static com.beacon.api.hazards.Hazard.NO2;
import static com.beacon.api.hazards.Hazard.OZONE;
import static com.beacon.api.hazards.Hazard.PM25;
import static com.beacon.api.hazards.Hazard.POLLEN_GRASS;
import static com.beacon.api.hazards.Hazard.POLLEN_TREE;
import static com.beacon.api.hazards.Hazard.POLLEN_WEED;
import static com.beacon.api.hazards.Hazard.TRAFFIC_PROX;
import static com.beacon.api.profiles.HardAvoid.ACTIVE_CONSTRUCTION_FRONTAGE;
import static com.beacon.api.profiles.HardAvoid.GRADE_ABOVE_FIVE_PERCENT;
import static com.beacon.api.profiles.HardAvoid.GRADE_ABOVE_SIX_PERCENT;
import static com.beacon.api.profiles.HardAvoid.INDUSTRIAL_WITHIN_200M;

import com.beacon.api.hazards.Hazard;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

public final class ProfilePresets {

    private static final Map<ProfilePreset, ProfilePresetDefinition> PRESETS = Map.of(
            ProfilePreset.ASTHMA, definition(
                    weights(PM25, 3.0, OZONE, 3.0, TRAFFIC_PROX, 2.0,
                            CONSTRUCTION, 2.0, COLD_AIR, 1.5),
                    Set.of(),
                    20.0),
            ProfilePreset.ALLERGIES, definition(
                    weights(POLLEN_TREE, 3.0, POLLEN_GRASS, 3.0, POLLEN_WEED, 3.0,
                            HUMIDITY, 1.5),
                    Set.of(),
                    20.0),
            ProfilePreset.COPD, definition(
                    weights(PM25, 3.0, OZONE, 3.0, GRADE, 3.0,
                            TRAFFIC_PROX, 2.0, HEAT, 1.5, COLD_AIR, 1.5),
                    Set.of(GRADE_ABOVE_SIX_PERCENT),
                    6.0),
            ProfilePreset.CHEMICAL_SENSITIVITY, definition(
                    weights(INDUSTRIAL_PROX, 3.0, CONSTRUCTION, 3.0,
                            TRAFFIC_PROX, 1.5, NO2, 1.5),
                    Set.of(ACTIVE_CONSTRUCTION_FRONTAGE, INDUSTRIAL_WITHIN_200M),
                    20.0),
            ProfilePreset.CARDIAC, definition(
                    weights(PM25, 3.0, HEAT, 3.0, GRADE, 3.0, OZONE, 1.5),
                    Set.of(GRADE_ABOVE_FIVE_PERCENT),
                    5.0),
            ProfilePreset.NONE, definition(Map.of(), Set.of(), 20.0));

    private ProfilePresets() {
    }

    public static ProfilePresetDefinition get(ProfilePreset preset) {
        return PRESETS.get(preset);
    }

    public static Map<ProfilePreset, ProfilePresetDefinition> all() {
        return PRESETS;
    }

    private static ProfilePresetDefinition definition(
            Map<Hazard, Double> weights,
            Set<HardAvoid> hardAvoids,
            double maxGradePct) {
        return new ProfilePresetDefinition(weights, hardAvoids, maxGradePct);
    }

    private static Map<Hazard, Double> weights(Object... values) {
        Map<Hazard, Double> weights = new EnumMap<>(Hazard.class);
        for (int index = 0; index < values.length; index += 2) {
            weights.put((Hazard) values[index], (Double) values[index + 1]);
        }
        return weights;
    }
}
