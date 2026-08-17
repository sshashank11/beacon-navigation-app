package com.beacon.api.hazards;

import static com.graphhopper.json.Statement.If;
import static com.graphhopper.json.Statement.Op.MULTIPLY;

import com.graphhopper.util.CustomModel;
import com.graphhopper.util.JsonFeature;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class LiveHazardModelEnricher {

    private final com.beacon.api.observability.BeaconMetrics metrics;

    private final HazardFieldService hazardFields;

    public LiveHazardModelEnricher(HazardFieldService hazardFields,
            com.beacon.api.observability.BeaconMetrics metrics) {
        this.metrics = metrics;
        this.hazardFields = hazardFields;
    }

    public CustomModel attach(
            CustomModel model,
            Map<String, Double> hazardWeights,
            double conservatism
    ) {
        List<JsonFeature> enabledAreas = hazardFields.currentAreas().stream()
                .filter(area -> hazardWeights.getOrDefault(hazard(area), 0.0) > 0.0)
                .toList();
        // Every polygon is a point-in-polygon test per edge, so how close this
        // runs to the configured cap is worth watching.
        metrics.recordHazardPolygons(enabledAreas.size());
        addAreas(model, enabledAreas);
        for (JsonFeature area : enabledAreas) {
            double weight = hazardWeights.get(hazard(area));
            int severity = ((Number) area.getProperty("severity")).intValue();
            double multiplier = priorityMultiplier(weight, conservatism, severity);
            model.addToPriority(If(
                    "in_" + area.getId(),
                    MULTIPLY,
                    String.format(Locale.ROOT, "%.4f", multiplier)));
        }
        return model;
    }

    public CustomModel attachHardAvoid(CustomModel model, String hazard) {
        List<JsonFeature> avoidedAreas = hazardFields.currentAreas().stream()
                .filter(area -> hazard.equals(hazard(area)))
                .toList();
        addAreas(model, avoidedAreas);
        for (JsonFeature area : avoidedAreas) {
            model.addToPriority(If("in_" + area.getId(), MULTIPLY, "0"));
        }
        return model;
    }

    static double priorityMultiplier(double weight, double conservatism, int severity) {
        double normalizedWeight = Math.min(Math.max(weight * conservatism / 3.0, 0.0), 1.0);
        return Math.max(0.1, 1.0 - severity * 0.1875 * normalizedWeight);
    }

    private static String hazard(JsonFeature area) {
        return String.valueOf(area.getProperty("hazard"));
    }

    private static void addAreas(CustomModel model, List<JsonFeature> areas) {
        Set<String> existingIds = model.getAreas().getFeatures().stream()
                .map(JsonFeature::getId)
                .collect(Collectors.toSet());
        areas.stream()
                .filter(area -> existingIds.add(area.getId()))
                .forEach(model.getAreas().getFeatures()::add);
    }
}
