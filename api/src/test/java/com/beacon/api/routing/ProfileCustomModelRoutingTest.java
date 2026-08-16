package com.beacon.api.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.beacon.api.conditions.SeasonalGates;
import com.beacon.api.hazards.Hazard;
import com.beacon.api.hazards.HazardFieldService;
import com.beacon.api.hazards.LiveHazardModelEnricher;
import com.beacon.api.profiles.CustomModelBuilder;
import com.beacon.api.profiles.TriggerProfile;
import com.beacon.api.routing.score.SegmentScoreIndex;
import com.graphhopper.GHRequest;
import com.graphhopper.GraphHopper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileCustomModelRoutingTest {

    @TempDir
    Path tempDirectory;

    @Test
    void profileModelRoutesAroundHighExposureAndExcessGrade() throws IOException {
        Path osmPath = tempDirectory.resolve("profile-corridors.osm");
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
        SegmentScoreIndex scores = new SegmentScoreIndex(2);
        scores.put(10, 95, 0, 0, 0, 0, 0, 0, 8, 0);
        scores.put(20, 10, 0, 0, 0, 0, 0, 0, 2, 0);
        GraphHopper hopper = new RoutingConfig().graphHopper(
                new RoutingProperties(osmPath.toString(), graphPath.toString()),
                scores);

        try {
            TriggerProfile profile = new TriggerProfile(
                    UUID.randomUUID(),
                    "Sensitive walker",
                    RouteMode.FOOT,
                    Map.of(Hazard.PM25, 3.0),
                    Set.of(),
                    5.0,
                    0.25,
                    1.0);
            HazardFieldService fields = mock(HazardFieldService.class);
            when(fields.currentAreas()).thenReturn(List.of());
            var model = new CustomModelBuilder(new LiveHazardModelEnricher(fields))
                    .build(profile, 1.0, new SeasonalGates(false, false, Set.of()));
            GHRequest request = new GHRequest(40.7500, -73.9900, 40.7501, -73.9860)
                    .setProfile("foot")
                    .setLocale("en-US")
                    .setCustomModel(model);

            var response = hopper.route(request);

            assertThat(response.getErrors()).isEmpty();
            double maximumLatitude = java.util.Arrays.stream(
                            response.getBest().getPoints().toLineString(false).getCoordinates())
                    .mapToDouble(point -> point.y)
                    .max()
                    .orElseThrow();
            assertThat(maximumLatitude).isGreaterThan(40.7510);
        } finally {
            hopper.close();
        }
    }
}
