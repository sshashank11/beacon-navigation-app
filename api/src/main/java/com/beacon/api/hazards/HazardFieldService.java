package com.beacon.api.hazards;

import com.graphhopper.util.JsonFeature;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.springframework.stereotype.Service;

@Service
public class HazardFieldService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(15);

    private final HazardFieldRepository fields;
    private final HazardAreaCache cache;

    public HazardFieldService(HazardFieldRepository fields, HazardAreaCache cache) {
        this.fields = fields;
        this.cache = cache;
    }

    public List<JsonFeature> currentAreas() {
        return currentEntries().stream()
                .map(this::toJsonFeature)
                .toList();
    }

    public List<HazardArea> currentAreaSummaries() {
        return currentEntries().stream()
                .map(entry -> new HazardArea(
                        entry.id(),
                        areaName(entry),
                        entry.hazard(),
                        entry.severity(),
                        entry.bandMin(),
                        entry.bandMax(),
                        entry.observedAt()))
                .toList();
    }

    private List<HazardAreaCacheEntry> currentEntries() {
        return cache.get().orElseGet(() -> {
            List<HazardAreaCacheEntry> entries = fields.findLatestFieldRows().stream()
                    .map(field -> new HazardAreaCacheEntry(
                            field.getId(),
                            field.getHazard(),
                            field.getSeverity(),
                            field.getBandMin(),
                            field.getBandMax(),
                            field.getObservedAt(),
                            field.getGeometryWkt()))
                    .toList();
            cache.put(entries, CACHE_TTL);
            return entries;
        });
    }

    private JsonFeature toJsonFeature(HazardAreaCacheEntry entry) {
        try {
            Geometry geometry = new WKTReader().read(entry.geometryWkt());
            return new JsonFeature(
                    areaName(entry),
                    "Feature",
                    geometry.getEnvelopeInternal(),
                    geometry,
                    Map.of(
                            "hazard", entry.hazard(),
                            "severity", entry.severity(),
                            "bandMin", entry.bandMin(),
                            "bandMax", entry.bandMax(),
                            "observedAt", entry.observedAt().toString()));
        } catch (ParseException exception) {
            throw new IllegalStateException("Invalid hazard field geometry for row " + entry.id(), exception);
        }
    }

    private String areaName(HazardAreaCacheEntry entry) {
        return entry.hazard() + "_" + switch (entry.severity()) {
            case 1 -> "low";
            case 2 -> "moderate";
            case 3 -> "high";
            case 4 -> "severe";
            default -> throw new IllegalArgumentException("Unsupported severity " + entry.severity());
        };
    }
}
