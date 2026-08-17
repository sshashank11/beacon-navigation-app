package com.beacon.api.routing;

import com.beacon.api.routing.score.SegmentScoreRepository;
import com.graphhopper.GraphHopper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Rebuilds the routing graph and swaps it in without downtime.
 *
 * <p>Static scores change when the pipeline reruns, and encoded values are
 * fixed at import, so picking up new scores means a new graph. Building into a
 * fresh directory and swapping the reference means routing keeps answering
 * throughout, and a failed import leaves the running graph untouched.
 */
@Service
@ConditionalOnProperty(prefix = "beacon.routing", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GraphRebuildService {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphRebuildService.class);

    private final RoutingConfig configuration = new RoutingConfig();
    private final RoutingProperties properties;
    private final SegmentScoreRepository scores;
    private final GraphHolder holder;
    private final AtomicBoolean rebuilding = new AtomicBoolean();

    public GraphRebuildService(
            RoutingProperties properties,
            SegmentScoreRepository scores,
            GraphHolder holder) {
        this.properties = properties;
        this.scores = scores;
        this.holder = holder;
    }

    public boolean isRebuilding() {
        return rebuilding.get();
    }

    /**
     * Reimports the graph from current scores and swaps it in.
     *
     * <p>Only one rebuild runs at a time: two concurrent imports would compete
     * for memory and disk for no benefit.
     */
    public RebuildResult rebuild() {
        if (!rebuilding.compareAndSet(false, true)) {
            throw new IllegalStateException("A graph rebuild is already running");
        }
        Instant started = Instant.now();
        Path directory = nextDirectory();
        try {
            GraphHopper replacement = configuration.buildReplacement(
                    properties, scores.loadIndex(), directory);
            holder.swap(replacement);
            LOGGER.info("Graph swapped in from {} after {}", directory,
                    java.time.Duration.between(started, Instant.now()));
            return new RebuildResult(
                    directory.toString(),
                    replacement.getBaseGraph().getEdges(),
                    java.time.Duration.between(started, Instant.now()).toMillis());
        } finally {
            rebuilding.set(false);
        }
    }

    /** A sibling directory, so the running graph's files are never touched. */
    private Path nextDirectory() {
        Path base = Path.of(properties.graphPath()).toAbsolutePath().normalize();
        return base.resolveSibling(base.getFileName() + "-" + Instant.now().toEpochMilli());
    }

    /**
     * Removes retired graph directories, keeping the newest few.
     *
     * <p>Each import is a fresh directory, so without this a nightly rebuild
     * fills the disk.
     */
    public int pruneOldDirectories(int keep) {
        Path base = Path.of(properties.graphPath()).toAbsolutePath().normalize();
        Path parent = base.getParent();
        if (parent == null) {
            return 0;
        }
        String prefix = base.getFileName() + "-";
        try (var entries = Files.list(parent)) {
            var candidates = entries
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                    .toList();
            int removed = 0;
            for (int index = keep; index < candidates.size(); index++) {
                deleteRecursively(candidates.get(index));
                removed++;
            }
            return removed;
        } catch (IOException exception) {
            LOGGER.warn("Could not prune old graph directories: {}", exception.getMessage());
            return 0;
        }
    }

    private static void deleteRecursively(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort; a leftover directory is not worth failing on.
                }
            });
        }
    }

    public record RebuildResult(String directory, int edges, long durationMs) {
    }
}
