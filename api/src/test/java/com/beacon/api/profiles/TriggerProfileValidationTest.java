package com.beacon.api.profiles;

import static org.assertj.core.api.Assertions.assertThat;

import com.beacon.api.hazards.Hazard;
import com.beacon.api.routing.RouteMode;
import jakarta.validation.Validation;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TriggerProfileValidationTest {

    @Test
    void rejectsWeightsAndConstraintsOutsideDocumentedRanges() {
        TriggerProfile profile = new TriggerProfile(
                UUID.randomUUID(),
                "Unsafe values",
                RouteMode.FOOT,
                Map.of(Hazard.PM25, 3.1, Hazard.NO2, Double.NaN),
                Set.of(),
                21.0,
                -0.1,
                0.0);

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var violations = factory.getValidator().validate(profile);
            assertThat(violations).extracting(violation -> violation.getPropertyPath().toString())
                    .contains("weights[PM25].<map value>", "finiteWeights", "maxGradePct",
                            "detourTolerance", "conservatism");
        }
    }

    @Test
    void acceptsAValidPresetBackedProfile() {
        ProfilePresetDefinition asthma = ProfilePresets.get(ProfilePreset.ASTHMA);
        TriggerProfile profile = new TriggerProfile(
                UUID.randomUUID(),
                "Morning walk",
                RouteMode.FOOT,
                asthma.weights(),
                asthma.hardAvoids(),
                asthma.maxGradePct(),
                0.25,
                1.0);

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(profile)).isEmpty();
        }
    }
}
