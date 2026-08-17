package com.beacon.api.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * The handful of numbers worth watching in this system.
 *
 * <p>Chosen because each one has a decision attached: routing latency says
 * whether the polygon budget is too generous, polygon count says how close the
 * live layer is to that budget, cache hit rate says whether speech credits will
 * last, and images scored says whether the CV pipeline is keeping up.
 */
@Component
public class BeaconMetrics {

    private final Timer routingLatency;
    private final AtomicInteger hazardPolygons = new AtomicInteger();
    private final AtomicInteger graphEdges = new AtomicInteger();
    private final MeterRegistry registry;

    public BeaconMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.routingLatency = Timer.builder("beacon.routing.latency")
                .description("Time to compute a route response")
                .publishPercentiles(0.5, 0.95)
                .register(registry);
        registry.gauge("beacon.hazard.polygons", hazardPolygons);
        registry.gauge("beacon.graph.edges", graphEdges);
    }

    public <T> T timeRouting(java.util.function.Supplier<T> work) {
        return routingLatency.record(work);
    }

    public void recordHazardPolygons(int count) {
        hazardPolygons.set(count);
    }

    public void recordGraphEdges(int edges) {
        graphEdges.set(edges);
    }

    public void recordSpeechRequest(boolean fromCache) {
        registry.counter("beacon.tts.requests", "cached", String.valueOf(fromCache)).increment();
    }

    public void recordImagesScored(int count) {
        registry.counter("beacon.imagery.scored").increment(count);
    }

    public void recordAnalysisRequested() {
        registry.counter("beacon.imagery.analysis.requested").increment();
    }
}
