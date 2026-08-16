package com.beacon.api.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.beacon.api.routing.score.SegmentScoreIndex;
import com.graphhopper.GHRequest;
import com.graphhopper.GraphHopper;
import com.graphhopper.ResponsePath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Pm25RouteDivergenceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void cleanestVariantAvoidsShortHighPm25Corridor() throws IOException {
        Path osmPath = tempDirectory.resolve("two-corridors.osm");
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
        scores.put(10, 95, 0, 0, 0, 0, 0, 0);
        scores.put(20, 10, 0, 0, 0, 0, 0, 0);
        GraphHopper hopper = new RoutingConfig().graphHopper(
                new RoutingProperties(osmPath.toString(), graphPath.toString()),
                scores);

        try {
            var fastest = route(hopper, RouteVariant.FASTEST);
            var cleanest = route(hopper, RouteVariant.CLEANEST);

            assertThat(fastest.getPoints().toLineString(false))
                    .isNotEqualTo(cleanest.getPoints().toLineString(false));
            assertThat(fastest.getDistance()).isLessThan(cleanest.getDistance());
            double maximumLatitude = java.util.Arrays.stream(
                            cleanest.getPoints().toLineString(false).getCoordinates())
                    .mapToDouble(point -> point.y)
                    .max()
                    .orElseThrow();
            assertThat(maximumLatitude).isGreaterThan(40.7510);
        } finally {
            hopper.close();
        }
    }

    private static GHRequest request(RouteVariant variant) {
        GHRequest request = new GHRequest(40.7500, -73.9900, 40.7501, -73.9860)
                .setProfile("foot")
                .setLocale("en-US");
        variant.configure(request);
        return request;
    }

    private static ResponsePath route(GraphHopper hopper, RouteVariant variant) {
        var response = hopper.route(request(variant));
        assertThat(response.getErrors()).isEmpty();
        return response.getBest();
    }
}
