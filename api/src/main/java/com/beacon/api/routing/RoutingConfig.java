package com.beacon.api.routing;

import com.beacon.api.routing.score.ClearwayImportRegistry;
import com.beacon.api.routing.score.SegmentScoreIndex;
import com.beacon.api.routing.score.SegmentScoreRepository;
import com.beacon.api.routing.score.StaticScore;
import com.graphhopper.GraphHopper;
import com.graphhopper.config.Profile;
import com.graphhopper.routing.ev.IntEncodedValue;
import com.graphhopper.util.JsonFeatureCollection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RoutingProperties.class)
@ConditionalOnProperty(prefix = "beacon.routing", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RoutingConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(RoutingConfig.class);
    private static final String ENCODED_VALUES = String.join(",",
            "foot_access",
            "foot_average_speed",
            "foot_priority",
            "hike_rating",
            "mtb_rating",
            "bike_access",
            "bike_average_speed",
            "bike_priority",
            "roundabout",
            "country",
            "road_class",
            "foot_road_access",
            "bike_road_access",
            StaticScore.PM25.encodedValueName(),
            StaticScore.NO2.encodedValueName(),
            StaticScore.OZONE.encodedValueName(),
            StaticScore.TRAFFIC.encodedValueName(),
            StaticScore.INDUSTRIAL.encodedValueName(),
            StaticScore.SHADE.encodedValueName(),
            StaticScore.POLLEN.encodedValueName(),
            StaticScore.GRADE.encodedValueName(),
            StaticScore.INDUSTRIAL_WITHIN_200M.encodedValueName(),
            StaticScore.SKY_VIEW.encodedValueName(),
            StaticScore.CROWD.encodedValueName(),
            ClearwayImportRegistry.SCORE_IMPORT_UNIT);

    @Bean(destroyMethod = "close")
    public GraphHopper graphHopper(
            RoutingProperties properties,
            SegmentScoreRepository scoreRepository) {
        return graphHopper(properties, scoreRepository.loadIndex());
    }

    GraphHopper graphHopper(RoutingProperties properties, SegmentScoreIndex scores) {
        Path osmPath = requireOsmFile(properties.osmPath());
        Path graphPath = prepareGraphPath(properties.graphPath());

        GraphHopper hopper = new GraphHopper()
                .setOSMFile(osmPath.toString())
                .setGraphHopperLocation(graphPath.toString())
                .setEncodedValuesString(ENCODED_VALUES)
                .setImportRegistry(new ClearwayImportRegistry(scores))
                .setProfiles(profiles());

        LOGGER.info("Importing or loading GraphHopper graph at {} from {}", graphPath, osmPath);
        try {
            hopper.importOrLoad();
            LOGGER.info("GraphHopper graph ready with {} nodes and {} edges",
                    hopper.getBaseGraph().getNodes(), hopper.getBaseGraph().getEdges());
            long scoredEdges = verifyStaticScores(hopper, scores.size());
            LOGGER.info("Loaded static scores for {} OSM ways; {} graph edges have a non-zero score",
                    scores.size(), scoredEdges);
            return hopper;
        } catch (RuntimeException exception) {
            hopper.close();
            throw exception;
        }
    }

    /**
     * The live graph, replaceable without downtime.
     *
     * <p>Exposed separately from the GraphHopper bean so a rebuild can publish
     * a new instance behind the same reference. Closing is the holder's job,
     * since it knows when in-flight requests have drained.
     */
    @Bean(destroyMethod = "close")
    public GraphHolder graphHolder(
            GraphHopper graphHopper,
            com.beacon.api.observability.BeaconMetrics metrics) {
        metrics.recordGraphEdges(graphHopper.getBaseGraph().getEdges());
        return new GraphHolder(graphHopper);
    }

    /** Imports a fresh graph into a new directory for a blue-green swap. */
    public GraphHopper buildReplacement(
            RoutingProperties properties,
            SegmentScoreIndex scores,
            Path graphDirectory) {
        Path osmPath = requireOsmFile(properties.osmPath());
        GraphHopper hopper = new GraphHopper()
                .setOSMFile(osmPath.toString())
                .setGraphHopperLocation(graphDirectory.toString())
                .setEncodedValuesString(ENCODED_VALUES)
                .setImportRegistry(new ClearwayImportRegistry(scores))
                .setProfiles(profiles());
        LOGGER.info("Building replacement graph at {}", graphDirectory);
        try {
            hopper.importOrLoad();
            verifyStaticScores(hopper, scores.size());
            return hopper;
        } catch (RuntimeException exception) {
            hopper.close();
            throw exception;
        }
    }

    private static long verifyStaticScores(GraphHopper hopper, int indexedWayCount) {
        List<IntEncodedValue> encodedValues = List.of(StaticScore.values()).stream()
                .map(score -> hopper.getEncodingManager()
                        .getIntEncodedValue(score.encodedValueName()))
                .toList();
        long scoredEdges = 0;
        var edges = hopper.getBaseGraph().getAllEdges();
        while (edges.next()) {
            if (encodedValues.stream().anyMatch(encodedValue -> edges.get(encodedValue) > 0)) {
                scoredEdges++;
            }
        }
        if (indexedWayCount > 0 && scoredEdges == 0) {
            throw new IllegalStateException(
                    "Static score index was populated but no graph edge received a score");
        }
        return scoredEdges;
    }

    private static List<Profile> profiles() {
        List<Profile> profiles = List.of(
                profile("foot", "foot.json"),
                profile("bike", "bike.json"));
        return GraphHopper.resolveCustomModelFiles("", profiles, new JsonFeatureCollection());
    }

    private static Profile profile(String name, String customModelFile) {
        return new Profile(name)
                .setWeighting("custom")
                .setCustomModel(null)
                .putHint("custom_model_files", List.of(customModelFile));
    }

    private static Path requireOsmFile(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            throw new IllegalStateException("beacon.routing.osm-path must be configured");
        }
        Path osmPath = Path.of(configuredPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(osmPath)) {
            throw new IllegalStateException("OSM extract does not exist: " + osmPath);
        }
        return osmPath;
    }

    private static Path prepareGraphPath(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            throw new IllegalStateException("beacon.routing.graph-path must be configured");
        }
        Path graphPath = Path.of(configuredPath).toAbsolutePath().normalize();
        Path parent = graphPath.getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create graph cache parent: " + parent, exception);
        }
        return graphPath;
    }
}
