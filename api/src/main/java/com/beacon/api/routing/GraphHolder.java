package com.beacon.api.routing;

import com.graphhopper.GraphHopper;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds the live graph so it can be replaced without downtime.
 *
 * <p>Encoded values are baked in at import, so any change to the static scores
 * needs a fresh import. Mutating a graph that requests are reading is not an
 * option, so a new one is built alongside the old and swapped in atomically.
 *
 * <p>The old instance is not closed immediately. A request that already took a
 * reference is still using it, and closing underneath it would fail that
 * request. Instead it is closed after in-flight work drains, or after a
 * deadline, whichever comes first.
 */
public class GraphHolder implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphHolder.class);
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(60);
    private static final long DRAIN_POLL_MS = 200;

    private final AtomicReference<GraphHopper> current = new AtomicReference<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicReference<Instant> loadedAt = new AtomicReference<>(Instant.now());

    public GraphHolder(GraphHopper initial) {
        current.set(initial);
    }

    public GraphHopper get() {
        return current.get();
    }

    public Instant loadedAt() {
        return loadedAt.get();
    }

    public int inFlight() {
        return inFlight.get();
    }

    /**
     * Runs work against a stable reference to the graph.
     *
     * <p>Taking the reference once and counting it keeps a swap from pulling
     * the graph out from under a routing call halfway through.
     */
    public <T> T withGraph(java.util.function.Function<GraphHopper, T> work) {
        GraphHopper graph = current.get();
        inFlight.incrementAndGet();
        try {
            return work.apply(graph);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    /**
     * Publishes a freshly imported graph and retires the previous one.
     *
     * @return the replaced instance, already closed
     */
    public GraphHopper swap(GraphHopper replacement) {
        if (replacement == null) {
            throw new IllegalArgumentException("Replacement graph must not be null");
        }
        GraphHopper previous = current.getAndSet(replacement);
        loadedAt.set(Instant.now());
        if (previous == null || previous == replacement) {
            return previous;
        }

        drain();
        LOGGER.info("Retiring previous graph after swap");
        previous.close();
        return previous;
    }

    private void drain() {
        Instant deadline = Instant.now().plus(DRAIN_TIMEOUT);
        while (inFlight.get() > 0 && Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(DRAIN_POLL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (inFlight.get() > 0) {
            LOGGER.warn(
                    "Closing the previous graph with {} request(s) still in flight after {}",
                    inFlight.get(),
                    DRAIN_TIMEOUT);
        }
    }

    @Override
    public void close() {
        GraphHopper graph = current.getAndSet(null);
        if (graph != null) {
            graph.close();
        }
    }
}
