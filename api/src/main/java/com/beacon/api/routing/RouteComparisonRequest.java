package com.beacon.api.routing;

import com.beacon.api.hazards.Hazard;
import com.beacon.api.profiles.HardAvoid;
import com.beacon.api.profiles.ProfilePreset;
import com.beacon.api.profiles.ProfilePresetDefinition;
import com.beacon.api.profiles.ProfilePresets;
import com.beacon.api.profiles.TriggerProfile;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record RouteComparisonRequest(
        @NotNull @Size(min = 2, max = 2) List<@NotNull Double> origin,
        @NotNull @Size(min = 2, max = 2) List<@NotNull Double> destination,
        @NotNull @Valid RouteMode mode,
        @NotNull ProfilePreset preset,
        @NotNull @Size(max = 15) Map<
                @NotNull Hazard,
                @NotNull @DecimalMin("0.0") @DecimalMax("3.0") Double> weights,
        @JsonProperty("hard_avoids") @NotNull Set<@NotNull HardAvoid> hardAvoids,
        @JsonProperty("max_grade_pct") @NotNull @DecimalMin("0.0") @DecimalMax("20.0")
        Double maxGradePct,
        @JsonProperty("detour_tolerance") @NotNull @DecimalMin("0.0") @DecimalMax("2.0")
        Double detourTolerance,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) @DecimalMax("2.0")
        Double conservatism
) {

    public RouteComparisonRequest {
        preset = preset == null ? ProfilePreset.NONE : preset;
        ProfilePresetDefinition defaults = ProfilePresets.get(preset);
        Map<Hazard, Double> mergedWeights = new EnumMap<>(Hazard.class);
        mergedWeights.putAll(defaults.weights());
        if (weights != null) {
            mergedWeights.putAll(weights);
        }
        weights = Map.copyOf(mergedWeights);
        hardAvoids = hardAvoids == null ? defaults.hardAvoids() : Set.copyOf(hardAvoids);
        maxGradePct = maxGradePct == null ? defaults.maxGradePct() : maxGradePct;
        detourTolerance = detourTolerance == null ? 0.25 : detourTolerance;
        conservatism = conservatism == null ? 1.0 : conservatism;
    }

    @AssertTrue(message = "weights must contain only finite values")
    public boolean hasFiniteWeights() {
        return weights.values().stream().allMatch(Double::isFinite);
    }

    TriggerProfile toProfile() {
        return new TriggerProfile(
                new UUID(0L, 0L),
                "Route comparison",
                mode,
                weights,
                hardAvoids,
                maxGradePct,
                detourTolerance,
                conservatism);
    }
}
