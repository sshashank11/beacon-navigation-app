package com.beacon.api.conditions;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ConditionsService {

    private static final int LOOKBACK_HOURS = 24;

    private final CitywideReadingRepository readings;
    private final NwsAlertRepository alerts;
    private final Clock clock;

    @Autowired
    public ConditionsService(CitywideReadingRepository readings, NwsAlertRepository alerts) {
        this(readings, alerts, Clock.systemUTC());
    }

    ConditionsService(CitywideReadingRepository readings, NwsAlertRepository alerts, Clock clock) {
        this.readings = readings;
        this.alerts = alerts;
        this.clock = clock;
    }

    public ConditionSnapshot currentSnapshot() {
        List<CitywideReading> latest = readings.findLatestPerStation(LOOKBACK_HOURS);
        Map<String, List<CitywideReading>> byHazard = latest.stream()
                .collect(Collectors.groupingBy(reading -> reading.getId().getHazard()));

        List<HazardCondition> hazards = byHazard.entrySet().stream()
                .map(entry -> toCondition(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(HazardCondition::hazard))
                .toList();
        List<AirQualityCondition> airQuality = airQuality(hazards);
        PollenCondition pollen = new PollenCondition(
                valueFor(hazards, "pollen_tree"),
                valueFor(hazards, "pollen_grass"),
                valueFor(hazards, "pollen_weed"));
        WeatherCondition weather = new WeatherCondition(
                valueFor(hazards, "heat"),
                valueFor(hazards, "humidity"),
                valueFor(hazards, "wind_speed"),
                valueFor(hazards, "wind_bearing"));
        List<AlertCondition> activeAlerts = alerts.findActive().stream()
                .map(ConditionsService::toAlertCondition)
                .toList();

        return new ConditionSnapshot(
                clock.instant(),
                hazards,
                airQuality,
                pollen,
                weather,
                activeAlerts,
                summarize(hazards, airQuality, pollen, activeAlerts));
    }

    public SeasonalGates seasonalGates() {
        List<HazardCondition> hazards = readings.findLatestPerStation(LOOKBACK_HOURS).stream()
                .collect(Collectors.groupingBy(reading -> reading.getId().getHazard()))
                .entrySet().stream()
                .map(entry -> toCondition(entry.getKey(), entry.getValue()))
                .toList();
        Double temperature = valueFor(hazards, "heat");
        Set<String> activePollen = new HashSet<>();
        for (String hazard : List.of("pollen_tree", "pollen_grass", "pollen_weed")) {
            Double upi = valueFor(hazards, hazard);
            if (upi != null && upi >= 2.0) {
                activePollen.add(hazard);
            }
        }
        return new SeasonalGates(
                temperature != null && temperature > 27.0,
                Set.copyOf(activePollen));
    }

    private HazardCondition toCondition(String hazard, List<CitywideReading> hazardReadings) {
        double meanValue = hazardReadings.stream()
                .mapToDouble(CitywideReading::getValue)
                .average()
                .orElse(0.0);
        Instant latestObservedAt = hazardReadings.stream()
                .map(reading -> reading.getId().getObservedAt())
                .max(Instant::compareTo)
                .orElse(Instant.EPOCH);
        String unit = hazardReadings.getFirst().getUnit();
        String source = hazardReadings.stream()
                .map(CitywideReading::getSource)
                .distinct()
                .sorted()
                .collect(Collectors.joining(","));

        return new HazardCondition(hazard, meanValue, unit, hazardReadings.size(), latestObservedAt, source);
    }

    private List<AirQualityCondition> airQuality(List<HazardCondition> hazards) {
        return hazards.stream()
                .filter(condition -> condition.hazard().endsWith("_aqi"))
                .map(condition -> {
                    int aqi = (int) Math.round(condition.meanValue());
                    return new AirQualityCondition(
                            condition.hazard().replace("_aqi", ""),
                            aqi,
                            aqiCategory(aqi),
                            condition.latestObservedAt());
                })
                .sorted(Comparator.comparingInt(AirQualityCondition::aqi).reversed())
                .toList();
    }

    private static AlertCondition toAlertCondition(NwsAlert alert) {
        return new AlertCondition(
                alert.getId(),
                alert.getEvent(),
                alert.getHeadline(),
                alert.getSeverity(),
                alert.getUrgency(),
                alert.getOnset(),
                alert.getExpiresAt());
    }

    private String summarize(
            List<HazardCondition> hazards,
            List<AirQualityCondition> airQuality,
            PollenCondition pollen,
            List<AlertCondition> activeAlerts
    ) {
        List<String> sentences = new ArrayList<>();
        if (!airQuality.isEmpty()) {
            AirQualityCondition highest = airQuality.getFirst();
            sentences.add("Air quality is " + highest.category().toLowerCase()
                    + " (" + highest.pollutant().toUpperCase() + " AQI " + highest.aqi() + ").");
        }
        PollenSummary pollenSummary = highestPollen(pollen);
        if (pollenSummary != null && pollenSummary.value() >= 2.0) {
            sentences.add(capitalize(pollenSummary.type()) + " pollen is "
                    + pollenCategory(pollenSummary.value()).toLowerCase() + ".");
        }
        if (!activeAlerts.isEmpty()) {
            AlertCondition alert = activeAlerts.getFirst();
            sentences.add("An active NWS " + alert.event() + " is in effect.");
        }
        if (sentences.isEmpty()) {
            return hazards.isEmpty()
                    ? "No live environmental readings are available yet."
                    : "Current environmental conditions are available with no elevated citywide signal.";
        }
        return String.join(" ", sentences);
    }

    private static Double valueFor(List<HazardCondition> hazards, String hazard) {
        return hazards.stream()
                .filter(condition -> condition.hazard().equals(hazard))
                .map(HazardCondition::meanValue)
                .findFirst()
                .orElse(null);
    }

    static String aqiCategory(int aqi) {
        if (aqi <= 50) {
            return "Good";
        }
        if (aqi <= 100) {
            return "Moderate";
        }
        if (aqi <= 150) {
            return "Unhealthy for sensitive groups";
        }
        if (aqi <= 200) {
            return "Unhealthy";
        }
        if (aqi <= 300) {
            return "Very unhealthy";
        }
        return "Hazardous";
    }

    private static String pollenCategory(double upi) {
        int rounded = Math.max(0, Math.min(5, (int) Math.round(upi)));
        return switch (rounded) {
            case 0 -> "None";
            case 1 -> "Very low";
            case 2 -> "Low";
            case 3 -> "Moderate";
            case 4 -> "High";
            default -> "Very high";
        };
    }

    private static PollenSummary highestPollen(PollenCondition pollen) {
        return List.of(
                        new PollenSummary("tree", pollen.treeUpi()),
                        new PollenSummary("grass", pollen.grassUpi()),
                        new PollenSummary("weed", pollen.weedUpi()))
                .stream()
                .filter(item -> item.value() != null)
                .max(Comparator.comparingDouble(PollenSummary::value))
                .orElse(null);
    }

    private static String capitalize(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private record PollenSummary(String type, Double value) {
    }
}
