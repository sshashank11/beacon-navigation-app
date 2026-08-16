package com.beacon.api.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.beacon.api.hazards.Hazard;
import com.beacon.api.profiles.ProfilePreset;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RouteComparisonRequestTest {

    @Test
    void presetSeedsWeightsAndExplicitValuesOverrideThem() {
        RouteComparisonRequest request = new RouteComparisonRequest(
                List.of(40.75, -73.99),
                List.of(40.76, -73.98),
                RouteMode.FOOT,
                ProfilePreset.ASTHMA,
                Map.of(Hazard.PM25, 1.5),
                null,
                null,
                null,
                null);

        assertThat(request.weights())
                .containsEntry(Hazard.PM25, 1.5)
                .containsEntry(Hazard.OZONE, 3.0);
        assertThat(request.hardAvoids()).isEmpty();
        assertThat(request.maxGradePct()).isEqualTo(20.0);
        assertThat(request.detourTolerance()).isEqualTo(0.25);
        assertThat(request.conservatism()).isEqualTo(1.0);
        assertThat(request.toProfile().getMode()).isEqualTo(RouteMode.FOOT);
    }

    @Test
    void deserializesCanonicalHazardAndHardAvoidKeys() throws JsonProcessingException {
        RouteComparisonRequest request = JsonMapper.builder().build().readValue("""
                {
                  "origin": [40.75, -73.99],
                  "destination": [40.76, -73.98],
                  "mode": "foot",
                  "preset": "chemical_sensitivity",
                  "weights": {"pm25": 2.5},
                  "hard_avoids": ["active_construction_frontage"],
                  "max_grade_pct": 8,
                  "detour_tolerance": 0.2,
                  "conservatism": 1.0
                }
                """, RouteComparisonRequest.class);

        assertThat(request.preset()).isEqualTo(ProfilePreset.CHEMICAL_SENSITIVITY);
        assertThat(request.weights()).containsEntry(Hazard.PM25, 2.5);
        assertThat(request.hardAvoids())
                .containsExactly(com.beacon.api.profiles.HardAvoid.ACTIVE_CONSTRUCTION_FRONTAGE);
    }
}
