package com.beacon.api.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.beacon.api.routing.score.SegmentScoreIndex;
import com.beacon.api.routing.score.StaticScore;
import com.graphhopper.GraphHopper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RoutingConfigTest {

    @TempDir
    Path tempDirectory;

    @Test
    void importsAndReloadsGraphCacheWithFootAndBikeProfiles() throws IOException {
        Path osmPath = tempDirectory.resolve("streets.osm");
        Path graphPath = tempDirectory.resolve("graph-cache");
        Files.writeString(osmPath, """
                <?xml version="1.0" encoding="UTF-8"?>
                <osm version="0.6" generator="beacon-test">
                  <node id="1" lat="40.7500" lon="-73.9900"/>
                  <node id="2" lat="40.7510" lon="-73.9900"/>
                  <way id="10">
                    <nd ref="1"/>
                    <nd ref="2"/>
                    <tag k="highway" v="residential"/>
                  </way>
                </osm>
                """);
        RoutingProperties properties = new RoutingProperties(osmPath.toString(), graphPath.toString());
        RoutingConfig configuration = new RoutingConfig();
        SegmentScoreIndex scores = new SegmentScoreIndex(1);
        scores.put(10, 82, 61, 42, 73, 54, 35, 16, 7, 100);

        GraphHopper imported = configuration.graphHopper(properties, scores);
        try {
            assertThat(imported.getFullyLoaded()).isTrue();
            assertThat(imported.getProfiles()).extracting("name").containsExactly("foot", "bike");
            assertThat(imported.getProfiles()).allSatisfy(profile -> {
                assertThat(profile.getWeighting()).isEqualTo("custom");
                assertThat(profile.getCustomModel().getSpeed()).isNotEmpty();
            });
            assertThat(imported.getBaseGraph().getEdges()).isPositive();
            assertThat(imported.getEncodingManager().hasEncodedValue(
                    StaticScore.PM25.encodedValueName())).isTrue();
            assertThat(imported.getEncodingManager().hasEncodedValue(
                    StaticScore.GRADE.encodedValueName())).isTrue();
            var edges = imported.getBaseGraph().getAllEdges();
            assertThat(edges.next()).isTrue();
            assertThat(edges.get(imported.getEncodingManager().getIntEncodedValue(
                    StaticScore.PM25.encodedValueName()))).isEqualTo(82);
        } finally {
            imported.close();
        }

        assertThat(graphPath).isDirectory();
        assertThat(graphPath.resolve("properties")).isRegularFile();

        GraphHopper reloaded = configuration.graphHopper(properties, scores);
        try {
            assertThat(reloaded.getFullyLoaded()).isTrue();
            assertThat(reloaded.getBaseGraph().getEdges()).isPositive();
            var edges = reloaded.getBaseGraph().getAllEdges();
            assertThat(edges.next()).isTrue();
            assertThat(edges.get(reloaded.getEncodingManager().getIntEncodedValue(
                    StaticScore.PM25.encodedValueName()))).isEqualTo(82);
        } finally {
            reloaded.close();
        }
    }
}
