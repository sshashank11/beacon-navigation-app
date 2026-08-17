package com.beacon.api.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.beacon.api.conditions.CitywideReading;
import com.beacon.api.conditions.SeasonalGates;
import com.beacon.api.hazards.Hazard;
import com.beacon.api.hazards.HazardFieldService;
import com.beacon.api.hazards.LiveHazardModelEnricher;
import com.beacon.api.profiles.CustomModelBuilder;
import com.beacon.api.profiles.TriggerProfile;
import com.beacon.api.routing.score.SegmentScoreIndex;
import com.graphhopper.GHRequest;
import com.graphhopper.GraphHopper;
import com.graphhopper.ResponsePath;
import com.graphhopper.util.JsonFeature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.io.WKTReader;

class SmokeDayRouteShiftTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-08-16T16:00:00Z");

    @TempDir
    Path tempDirectory;

    @Test
    void wildfirePm25FixtureShiftsTheRouteAwayFromTheLiveHazardField() throws Exception {
        Path osmPath = tempDirectory.resolve("smoke-day.osm");
        Path graphPath = tempDirectory.resolve("graph-cache");
        Files.writeString(osmPath, """
                <?xml version="1.0" encoding="UTF-8"?>
                <osm version="0.6" generator="beacon-test">
                  <node id="1" lat="40.7500" lon="-73.9900"/>
                  <node id="2" lat="40.75005" lon="-73.9880"/>
                  <node id="3" lat="40.7501" lon="-73.9860"/>
                  <node id="4" lat="40.7512" lon="-73.9880"/>
                  <way id="10">
                    <nd ref="1"/><nd ref="2"/><nd ref="3"/>
                    <tag k="highway" v="residential"/>
                  </way>
                  <way id="20">
                    <nd ref="1"/><nd ref="4"/><nd ref="3"/>
                    <tag k="highway" v="residential"/>
                  </way>
                </osm>
                """);
        GraphHopper hopper = new RoutingConfig().graphHopper(
                new RoutingProperties(osmPath.toString(), graphPath.toString()),
                SegmentScoreIndex.empty());

        try {
            DayFixture clearDay = new DayFixture(
                    new CitywideReading("pm25", "fixture", OBSERVED_AT, "fixture", 8.0f, "ug/m3"),
                    List.of());
            DayFixture smokeDay = new DayFixture(
                    new CitywideReading("pm25", "fixture", OBSERVED_AT, "fixture", 250.0f, "ug/m3"),
                    List.of(smokeArea()));

            ResponsePath clearPath = route(hopper, clearDay);
            ResponsePath smokePath = route(hopper, smokeDay);

            assertThat(clearDay.reading().getValue()).isLessThan(12.0f);
            assertThat(smokeDay.reading().getValue()).isGreaterThanOrEqualTo(150.0f);
            assertThat(smokePath.getPoints().toLineString(false))
                    .isNotEqualTo(clearPath.getPoints().toLineString(false));
            assertThat(smokePath.getDistance()).isGreaterThan(clearPath.getDistance() * 1.05);
            assertThat(maximumLatitude(smokePath)).isGreaterThan(40.7510);
        } finally {
            hopper.close();
        }
    }

    private static ResponsePath route(GraphHopper hopper, DayFixture day) {
        TriggerProfile profile = new TriggerProfile(
                UUID.randomUUID(),
                "Smoke-sensitive walker",
                RouteMode.FOOT,
                Map.of(Hazard.PM25, 3.0),
                Set.of(),
                20.0,
                0.25,
                1.0);
        HazardFieldService fields = mock(HazardFieldService.class);
        when(fields.currentAreas()).thenReturn(day.areas());
        var model = new CustomModelBuilder(new LiveHazardModelEnricher(fields, new com.beacon.api.observability.BeaconMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry())))
                .build(profile, 2.0, new SeasonalGates(false, false, Set.of()));
        GHRequest request = new GHRequest(40.7500, -73.9900, 40.7501, -73.9860)
                .setProfile("foot")
                .setLocale("en-US")
                .setCustomModel(model);

        var response = hopper.route(request);
        assertThat(response.getErrors()).isEmpty();
        return response.getBest();
    }

    private static JsonFeature smokeArea() throws Exception {
        var geometry = new WKTReader().read("""
                POLYGON ((
                  -73.9886 40.7498,
                  -73.9874 40.7498,
                  -73.9874 40.7503,
                  -73.9886 40.7503,
                  -73.9886 40.7498
                ))
                """);
        return new JsonFeature(
                "pm25_severe",
                "Feature",
                null,
                geometry,
                Map.of("hazard", "pm25", "severity", 4));
    }

    private static double maximumLatitude(ResponsePath path) {
        return Arrays.stream(path.getPoints().toLineString(false).getCoordinates())
                .mapToDouble(coordinate -> coordinate.y)
                .max()
                .orElseThrow();
    }

    private record DayFixture(CitywideReading reading, List<JsonFeature> areas) {
    }
}
