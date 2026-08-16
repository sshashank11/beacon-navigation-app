package com.beacon.api.hazards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.graphhopper.util.CustomModel;
import com.graphhopper.util.JsonFeature;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.GeometryFactory;

class LiveHazardModelEnricherTest {

    @Test
    void attachAddsOnlyWeightedHazardAreasAndPriorityRules() {
        HazardFieldService fields = mock(HazardFieldService.class);
        JsonFeature pm25 = feature("pm25_severe", "pm25", 4);
        JsonFeature pollen = feature("pollen_tree_high", "pollen_tree", 3);
        when(fields.currentAreas()).thenReturn(List.of(pm25, pollen));

        CustomModel model = new LiveHazardModelEnricher(fields)
                .attach(new CustomModel(), Map.of("pm25", 3.0, "pollen_tree", 0.0), 1.0);

        assertThat(model.getAreas().getFeatures()).containsExactly(pm25);
        assertThat(model.getPriority()).singleElement().satisfies(statement -> {
            assertThat(statement.condition()).isEqualTo("in_pm25_severe");
            assertThat(statement.value()).isEqualTo("0.2500");
        });
    }

    @Test
    void hardAvoidAddsAZeroPriorityRuleWithoutDuplicatingAnArea() {
        HazardFieldService fields = mock(HazardFieldService.class);
        JsonFeature construction = feature("construction_active", "construction", 3);
        when(fields.currentAreas()).thenReturn(List.of(construction));
        LiveHazardModelEnricher enricher = new LiveHazardModelEnricher(fields);
        CustomModel model = enricher.attach(
                new CustomModel(),
                Map.of("construction", 3.0),
                1.0);

        enricher.attachHardAvoid(model, "construction");

        assertThat(model.getAreas().getFeatures()).containsExactly(construction);
        assertThat(model.getPriority()).anySatisfy(statement -> {
            assertThat(statement.condition()).isEqualTo("in_construction_active");
            assertThat(statement.value()).isEqualTo("0");
        });
    }

    private static JsonFeature feature(String id, String hazard, int severity) {
        var geometry = new GeometryFactory().createPoint();
        return new JsonFeature(
                id,
                "Feature",
                geometry.getEnvelopeInternal(),
                geometry,
                Map.of("hazard", hazard, "severity", severity));
    }
}
