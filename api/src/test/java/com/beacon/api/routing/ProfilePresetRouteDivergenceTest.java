package com.beacon.api.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.beacon.api.conditions.SeasonalGates;
import com.beacon.api.hazards.HazardFieldService;
import com.beacon.api.hazards.LiveHazardModelEnricher;
import com.beacon.api.profiles.CustomModelBuilder;
import com.beacon.api.profiles.ProfilePreset;
import com.beacon.api.profiles.TriggerProfile;
import com.beacon.api.routing.score.SegmentScoreIndex;
import com.graphhopper.GHRequest;
import com.graphhopper.GraphHopper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.LineString;

class ProfilePresetRouteDivergenceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void asthmaAndAllergyProfilesChooseDifferentGeometries() throws IOException {
        Path osmPath = tempDirectory.resolve("profile-divergence.osm");
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
        scores.put(10, 95, 95, 95, 95, 0, 50, 0, 0, 0, 0, 0);
        scores.put(20, 0, 0, 0, 0, 0, 50, 95, 0, 0, 0, 0);
        GraphHopper hopper = new RoutingConfig().graphHopper(
                new RoutingProperties(osmPath.toString(), graphPath.toString()),
                scores);

        try {
            HazardFieldService fields = mock(HazardFieldService.class);
            when(fields.currentAreas()).thenReturn(List.of());
            CustomModelBuilder models = new CustomModelBuilder(new LiveHazardModelEnricher(fields, new com.beacon.api.observability.BeaconMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry())));
            SeasonalGates pollenSeason = new SeasonalGates(
                    false,
                    true,
                    Set.of("pollen_tree", "pollen_grass", "pollen_weed"));

            LineString asthmaGeometry = route(
                    hopper,
                    models,
                    profile(ProfilePreset.ASTHMA),
                    pollenSeason);
            LineString allergyGeometry = route(
                    hopper,
                    models,
                    profile(ProfilePreset.ALLERGIES),
                    pollenSeason);

            assertThat(asthmaGeometry).isNotEqualTo(allergyGeometry);
        } finally {
            hopper.close();
        }
    }

    private static TriggerProfile profile(ProfilePreset preset) {
        return new RouteComparisonRequest(
                List.of(40.7500, -73.9900),
                List.of(40.7501, -73.9860),
                RouteMode.FOOT,
                preset,
                null,
                null,
                null,
                0.25,
                1.0).toProfile();
    }

    private static LineString route(
            GraphHopper hopper,
            CustomModelBuilder models,
            TriggerProfile profile,
            SeasonalGates gates
    ) {
        GHRequest request = new GHRequest(40.7500, -73.9900, 40.7501, -73.9860)
                .setProfile("foot")
                .setLocale("en-US")
                .setCustomModel(models.build(profile, 2.0, gates));
        var response = hopper.route(request);
        assertThat(response.getErrors()).isEmpty();
        return response.getBest().getPoints().toLineString(false);
    }
}
