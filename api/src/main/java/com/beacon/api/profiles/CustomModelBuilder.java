package com.beacon.api.profiles;

import static com.graphhopper.json.Statement.ElseIf;
import static com.graphhopper.json.Statement.If;
import static com.graphhopper.json.Statement.Op.MULTIPLY;

import com.beacon.api.conditions.SeasonalGates;
import com.beacon.api.hazards.Hazard;
import com.beacon.api.hazards.LiveHazardModelEnricher;
import com.beacon.api.routing.score.StaticScore;
import com.graphhopper.util.CustomModel;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class CustomModelBuilder {

    private static final List<ExposureBand> EXPOSURE_BANDS = List.of(
            new ExposureBand(75, 1.0),
            new ExposureBand(50, 0.67),
            new ExposureBand(25, 0.33));
    private static final Map<Hazard, StaticScore> STATIC_SCORES = staticScores();
    /**
     * A canyon amplifies particulate exposure; it is not a hazard in itself,
     * so it carries less weight than the measured pollution score.
     */
    private static final double CANYON_SENSITIVITY = 0.5;

    private final LiveHazardModelEnricher liveHazards;

    public CustomModelBuilder(LiveHazardModelEnricher liveHazards) {
        this.liveHazards = liveHazards;
    }

    public CustomModel build(
            TriggerProfile profile,
            double weightScale,
            SeasonalGates gates
    ) {
        if (!Double.isFinite(weightScale) || weightScale < 0.0) {
            throw new IllegalArgumentException("weightScale must be finite and non-negative");
        }

        CustomModel model = new CustomModel();
        Map<Hazard, Double> effectiveWeights = effectiveWeights(profile, gates, weightScale);
        for (Map.Entry<Hazard, Double> entry : effectiveWeights.entrySet()) {
            StaticScore score = STATIC_SCORES.get(entry.getKey());
            if (score != null) {
                addExposureRules(
                        model,
                        score,
                        entry.getValue() * profile.getConservatism(),
                        false);
            }
        }
        addStreetCanyonRules(model, profile, effectiveWeights);
        if (effectiveWeights.getOrDefault(Hazard.POLLEN_TREE, 0.0) > 0.0) {
            addExposureRules(
                    model,
                    StaticScore.POLLEN,
                    effectiveWeights.get(Hazard.POLLEN_TREE) * profile.getConservatism(),
                    false);
        }
        if (effectiveWeights.getOrDefault(Hazard.SHADE_DEFICIT, 0.0) > 0.0) {
            addExposureRules(
                    model,
                    StaticScore.SHADE,
                    effectiveWeights.get(Hazard.SHADE_DEFICIT) * profile.getConservatism(),
                    true);
        }

        Map<String, Double> liveWeights = new java.util.HashMap<>();
        effectiveWeights.forEach((hazard, weight) -> liveWeights.put(hazard.key(), weight));
        liveHazards.attach(model, liveWeights, profile.getConservatism());
        addHardAvoids(model, profile);
        return model;
    }

    private void addHardAvoids(CustomModel model, TriggerProfile profile) {
        Set<String> gradeConditions = new LinkedHashSet<>();
        gradeConditions.add(gradeCondition(profile.getMaxGradePct()));
        Set<HardAvoid> hardAvoids = profile.getHardAvoids();
        if (hardAvoids.contains(HardAvoid.GRADE_ABOVE_FIVE_PERCENT)) {
            gradeConditions.add(gradeCondition(5.0));
        }
        if (hardAvoids.contains(HardAvoid.GRADE_ABOVE_SIX_PERCENT)) {
            gradeConditions.add(gradeCondition(6.0));
        }
        gradeConditions.forEach(condition -> model.addToPriority(If(condition, MULTIPLY, "0")));

        if (hardAvoids.contains(HardAvoid.INDUSTRIAL_WITHIN_200M)) {
            model.addToPriority(If(
                    StaticScore.INDUSTRIAL_WITHIN_200M.encodedValueName() + " > 0",
                    MULTIPLY,
                    "0"));
        }
        if (hardAvoids.contains(HardAvoid.ACTIVE_CONSTRUCTION_FRONTAGE)) {
            liveHazards.attachHardAvoid(model, Hazard.CONSTRUCTION.key());
        }
    }

    private static Map<Hazard, Double> effectiveWeights(
            TriggerProfile profile,
            SeasonalGates gates,
            double weightScale
    ) {
        Map<Hazard, Double> weights = new EnumMap<>(Hazard.class);
        profile.getWeights().forEach((hazard, weight) -> {
            double scaledWeight = weight * weightScale;
            if (scaledWeight > 0.0) {
                weights.put(hazard, scaledWeight);
            }
        });
        if (!gates.shadeActive()) {
            weights.remove(Hazard.SHADE_DEFICIT);
        }
        boolean treePollenActive = gates.treePollenSeasonActive()
                && gates.activePollenHazards().contains(Hazard.POLLEN_TREE.key());
        if (!treePollenActive) {
            weights.remove(Hazard.POLLEN_TREE);
        }
        for (Hazard pollen : List.of(Hazard.POLLEN_GRASS, Hazard.POLLEN_WEED)) {
            if (!gates.activePollenHazards().contains(pollen.key())) {
                weights.remove(pollen);
            }
        }
        return weights;
    }

    private static void addExposureRules(
            CustomModel model,
            StaticScore score,
            double sensitivity,
            boolean inverse
    ) {
        String encodedValue = score.encodedValueName();
        List<com.graphhopper.json.Statement> statements = new ArrayList<>();
        for (int index = 0; index < EXPOSURE_BANDS.size(); index++) {
            ExposureBand band = EXPOSURE_BANDS.get(index);
            String operator = inverse ? " < " : " > ";
            int threshold = inverse ? 100 - band.threshold() : band.threshold();
            String condition = encodedValue + operator + threshold;
            if (score.optional()) {
                // Never let an unmeasured segment match an exposure band.
                condition = score.presentCondition() + " && " + condition;
            }
            String multiplier = String.format(
                    Locale.ROOT,
                    "%.4f",
                    Math.max(0.1, 1.0 / (1.0 + sensitivity * band.exposureFraction())));
            statements.add(index == 0
                    ? If(condition, MULTIPLY, multiplier)
                    : ElseIf(condition, MULTIPLY, multiplier));
        }
        statements.forEach(model::addToPriority);
    }

    /**
     * Penalises street canyons for users who care about particulates.
     *
     * <p>A low sky view factor means the street is walled in, and walled-in
     * streets hold exhaust near the pavement instead of letting it disperse.
     * The blueprint treats this as a modifier on particulate exposure rather
     * than a hazard of its own, so it only applies when the profile actually
     * weights PM2.5, scaled by that weight.
     */
    private void addStreetCanyonRules(
            CustomModel model,
            TriggerProfile profile,
            Map<Hazard, Double> effectiveWeights
    ) {
        double particulateWeight = effectiveWeights.getOrDefault(Hazard.PM25, 0.0);
        if (particulateWeight <= 0.0) {
            return;
        }
        addExposureRules(
                model,
                StaticScore.SKY_VIEW,
                particulateWeight * CANYON_SENSITIVITY * profile.getConservatism(),
                true);
    }

    private static String gradeCondition(double threshold) {
        return StaticScore.GRADE.encodedValueName() + " > "
                + String.format(Locale.ROOT, "%.1f", threshold);
    }

    private static Map<Hazard, StaticScore> staticScores() {
        Map<Hazard, StaticScore> scores = new EnumMap<>(Hazard.class);
        scores.put(Hazard.PM25, StaticScore.PM25);
        scores.put(Hazard.NO2, StaticScore.NO2);
        scores.put(Hazard.OZONE, StaticScore.OZONE);
        scores.put(Hazard.TRAFFIC_PROX, StaticScore.TRAFFIC);
        scores.put(Hazard.INDUSTRIAL_PROX, StaticScore.INDUSTRIAL);
        scores.put(Hazard.GRADE, StaticScore.GRADE);
        scores.put(Hazard.CROWD_DENSITY, StaticScore.CROWD);
        return Map.copyOf(scores);
    }

    private record ExposureBand(int threshold, double exposureFraction) {
    }
}
