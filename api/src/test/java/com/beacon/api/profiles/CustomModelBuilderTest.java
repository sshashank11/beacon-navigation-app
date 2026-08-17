package com.beacon.api.profiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.beacon.api.conditions.SeasonalGates;
import com.beacon.api.hazards.Hazard;
import com.beacon.api.hazards.HazardFieldService;
import com.beacon.api.hazards.LiveHazardModelEnricher;
import com.beacon.api.routing.RouteMode;
import com.beacon.api.routing.score.StaticScore;
import com.graphhopper.json.Statement;
import com.graphhopper.util.CustomModel;
import com.graphhopper.util.JsonFeature;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.GeometryFactory;

class CustomModelBuilderTest {

    @Test
    void scalesStaticHazardPriorityByWeightAndConservatism() {
        CustomModelBuilder builder = builder(List.of());
        TriggerProfile profile = profile(
                Map.of(Hazard.PM25, 3.0),
                Set.of(),
                20.0,
                1.0);
        SeasonalGates gates = new SeasonalGates(false, false, Set.of());

        CustomModel balanced = builder.build(profile, 1.0, gates);
        CustomModel cleanest = builder.build(profile, 2.0, gates);

        double balancedMultiplier = multiplierFor(balanced, "clw_pm25 > 75");
        double cleanestMultiplier = multiplierFor(cleanest, "clw_pm25 > 75");
        assertThat(balancedMultiplier).isEqualTo(0.25);
        assertThat(cleanestMultiplier).isEqualTo(0.1429);
        assertThat(cleanestMultiplier).isLessThan(balancedMultiplier);
    }

    @Test
    void gatesShadeAndTreePollenRules() {
        CustomModelBuilder builder = builder(List.of());
        TriggerProfile profile = profile(
                Map.of(Hazard.POLLEN_TREE, 3.0, Hazard.SHADE_DEFICIT, 2.0),
                Set.of(),
                20.0,
                1.0);

        CustomModel inactive = builder.build(
                profile,
                1.0,
                new SeasonalGates(false, false, Set.of("pollen_tree")));
        CustomModel active = builder.build(
                profile,
                1.0,
                new SeasonalGates(true, true, Set.of("pollen_tree")));

        assertThat(inactive.getPriority())
                .noneMatch(statement -> statement.condition().startsWith("clw_pollen")
                        || statement.condition().startsWith("clw_shade"));
        assertThat(active.getPriority())
                .anyMatch(statement -> statement.condition().equals("clw_pollen > 75"))
                .anyMatch(statement -> statement.condition().equals("clw_shade < 25"));
    }

    @Test
    void emitsZeroPriorityRulesForEveryHardAvoid() {
        JsonFeature construction = feature("construction_active", "construction", 4);
        CustomModelBuilder builder = builder(List.of(construction));
        TriggerProfile profile = profile(
                Map.of(),
                Set.of(
                        HardAvoid.GRADE_ABOVE_FIVE_PERCENT,
                        HardAvoid.INDUSTRIAL_WITHIN_200M,
                        HardAvoid.ACTIVE_CONSTRUCTION_FRONTAGE),
                5.0,
                1.0);

        CustomModel model = builder.build(
                profile,
                1.0,
                new SeasonalGates(false, false, Set.of()));

        assertThat(model.getPriority())
                .filteredOn(statement -> statement.value().equals("0"))
                .extracting(Statement::condition)
                .containsExactlyInAnyOrder(
                        "clw_grade > 5.0",
                        "clw_industrial_within_200m > 0",
                        "in_construction_active");
        assertThat(model.getAreas().getFeatures()).containsExactly(construction);
    }

    @Test
    void everyImageryRuleExcludesSegmentsWithNoImagery() {
        CustomModelBuilder builder = builder(List.of());
        TriggerProfile profile = profile(
                Map.of(Hazard.PM25, 3.0, Hazard.CROWD_DENSITY, 2.0),
                Set.of(),
                20.0,
                1.0);

        CustomModel model = builder.build(profile, 1.0, gates());

        List<String> imageryConditions = model.getPriority().stream()
                .map(Statement::condition)
                .filter(condition -> condition != null
                        && (condition.contains("clw_svf") || condition.contains("clw_crowd")))
                .toList();

        assertThat(imageryConditions).isNotEmpty();
        // Without this guard the sentinel would match every band and the whole
        // unphotographed city would be penalised.
        assertThat(imageryConditions).allSatisfy(condition ->
                assertThat(condition).contains("< " + StaticScore.NO_DATA + " &&"));
    }

    @Test
    void crowdDensityWeightReachesTheImageryScore() {
        CustomModelBuilder builder = builder(List.of());
        TriggerProfile profile = profile(
                Map.of(Hazard.CROWD_DENSITY, 3.0), Set.of(), 20.0, 1.0);

        CustomModel model = builder.build(profile, 1.0, gates());

        assertThat(model.getPriority()).anySatisfy(statement ->
                assertThat(statement.condition()).contains("clw_crowd > 75"));
    }

    @Test
    void canyonPenaltyAppliesOnlyWhenParticulatesAreWeighted() {
        CustomModelBuilder builder = builder(List.of());

        CustomModel withParticulates = builder.build(
                profile(Map.of(Hazard.PM25, 3.0), Set.of(), 20.0, 1.0), 1.0, gates());
        CustomModel withoutParticulates = builder.build(
                profile(Map.of(Hazard.GRADE, 3.0), Set.of(), 20.0, 1.0), 1.0, gates());

        assertThat(conditions(withParticulates)).anyMatch(c -> c.contains("clw_svf"));
        assertThat(conditions(withoutParticulates)).noneMatch(c -> c.contains("clw_svf"));
    }

    @Test
    void canyonRulesFireOnLowSkyViewNotHigh() {
        CustomModelBuilder builder = builder(List.of());

        CustomModel model = builder.build(
                profile(Map.of(Hazard.PM25, 3.0), Set.of(), 20.0, 1.0), 1.0, gates());

        // Low sky view is the canyon; a wide open street must not be penalised.
        assertThat(conditions(model))
                .filteredOn(condition -> condition.contains("clw_svf"))
                .allSatisfy(condition -> assertThat(condition).contains("clw_svf < "));
    }

    private static SeasonalGates gates() {
        return new SeasonalGates(false, false, Set.of());
    }

    private static List<String> conditions(CustomModel model) {
        return model.getPriority().stream()
                .map(Statement::condition)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static CustomModelBuilder builder(List<JsonFeature> areas) {
        HazardFieldService fields = mock(HazardFieldService.class);
        when(fields.currentAreas()).thenReturn(areas);
        return new CustomModelBuilder(new LiveHazardModelEnricher(fields));
    }

    private static TriggerProfile profile(
            Map<Hazard, Double> weights,
            Set<HardAvoid> hardAvoids,
            double maxGrade,
            double conservatism
    ) {
        return new TriggerProfile(
                UUID.randomUUID(),
                "Test",
                RouteMode.FOOT,
                weights,
                hardAvoids,
                maxGrade,
                0.25,
                conservatism);
    }

    private static double multiplierFor(CustomModel model, String condition) {
        return model.getPriority().stream()
                .filter(statement -> statement.condition().equals(condition))
                .mapToDouble(statement -> Double.parseDouble(statement.value()))
                .findFirst()
                .orElseThrow();
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
