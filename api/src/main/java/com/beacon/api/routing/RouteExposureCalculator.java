package com.beacon.api.routing;

import com.beacon.api.routing.score.StaticScore;
import com.graphhopper.ResponsePath;
import com.graphhopper.util.DistanceCalcEarth;
import com.graphhopper.util.PointList;
import com.graphhopper.util.details.PathDetail;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RouteExposureCalculator {

    private static final Map<String, ExposureDefinition> EXPOSURES = exposures();

    private RouteExposureCalculator() {
    }

    static List<String> pathDetails() {
        return EXPOSURES.values().stream()
                .map(ExposureDefinition::encodedValue)
                .distinct()
                .toList();
    }

    static Map<String, Double> calculate(ResponsePath path) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (Map.Entry<String, ExposureDefinition> entry : EXPOSURES.entrySet()) {
            ExposureDefinition definition = entry.getValue();
            List<PathDetail> details = path.getPathDetails()
                    .getOrDefault(definition.encodedValue(), List.of());
            double mean = lengthWeightedMean(path.getPoints(), details);
            result.put(entry.getKey(), round(definition.inverse() ? 100.0 - mean : mean, 2));
        }
        return result;
    }

    static Map<String, Double> comparativeDiff(
            Map<String, Double> exposure,
            Map<String, Double> fastestExposure,
            double distance,
            double fastestDistance
    ) {
        Map<String, Double> result = new LinkedHashMap<>();
        fastestExposure.forEach((hazard, baseline) -> result.put(
                hazard,
                percentageChange(exposure.get(hazard), baseline)));
        result.put("distance", percentageChange(distance, fastestDistance));
        return result;
    }

    private static double lengthWeightedMean(PointList points, List<PathDetail> details) {
        double weightedScore = 0.0;
        double totalDistance = 0.0;
        for (PathDetail detail : details) {
            if (!(detail.getValue() instanceof Number value)) {
                continue;
            }
            double distance = distance(points, detail.getFirst(), detail.getLast());
            weightedScore += value.doubleValue() * distance;
            totalDistance += distance;
        }
        return totalDistance == 0.0 ? 0.0 : weightedScore / totalDistance;
    }

    private static double distance(PointList points, int first, int last) {
        int start = Math.max(0, first);
        int end = Math.min(last, points.size() - 1);
        double distance = 0.0;
        for (int index = start; index < end; index++) {
            distance += DistanceCalcEarth.DIST_EARTH.calcDist(
                    points.getLat(index),
                    points.getLon(index),
                    points.getLat(index + 1),
                    points.getLon(index + 1));
        }
        return distance;
    }

    private static Double percentageChange(double value, double baseline) {
        if (baseline == 0.0) {
            return value == 0.0 ? 0.0 : null;
        }
        return round((value - baseline) / baseline, 4);
    }

    private static double round(double value, int places) {
        double factor = Math.pow(10, places);
        return Math.round(value * factor) / factor;
    }

    private static Map<String, ExposureDefinition> exposures() {
        Map<String, ExposureDefinition> exposures = new LinkedHashMap<>();
        exposures.put("pm25", definition(StaticScore.PM25));
        exposures.put("no2", definition(StaticScore.NO2));
        exposures.put("ozone", definition(StaticScore.OZONE));
        exposures.put("traffic_prox", definition(StaticScore.TRAFFIC));
        exposures.put("industrial_prox", definition(StaticScore.INDUSTRIAL));
        exposures.put("shade_deficit", new ExposureDefinition(
                StaticScore.SHADE.encodedValueName(), true));
        exposures.put("pollen_tree", definition(StaticScore.POLLEN));
        exposures.put("grade", definition(StaticScore.GRADE));
        return Collections.unmodifiableMap(exposures);
    }

    private static ExposureDefinition definition(StaticScore score) {
        return new ExposureDefinition(score.encodedValueName(), false);
    }

    private record ExposureDefinition(String encodedValue, boolean inverse) {
    }
}
