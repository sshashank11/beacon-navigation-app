package com.beacon.api.analysis;

import java.io.IOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Emits each frame of an analysis over SSE as soon as it is available.
 *
 * <p>Already-scored frames go out on the first pass, which is the common case
 * on a corridor that has been scored offline. The rest are polled for while
 * the worker catches up. Polling is a deliberate choice over a Redis
 * subscription: the worker is a separate Python process writing to Postgres,
 * and a database read is the one signal both sides already agree on.
 */
@Component
public class AnalysisStreamer {

    private final RouteAnalysisService analysis;
    private final Duration pollInterval;
    private final Duration maxWait;
    private final Sleeper sleeper;

    @Autowired
    public AnalysisStreamer(
            RouteAnalysisService analysis,
            @Value("${beacon.imagery.stream-poll-ms:1000}") long pollIntervalMs,
            @Value("${beacon.imagery.stream-max-wait-ms:90000}") long maxWaitMs) {
        this(
                analysis,
                Duration.ofMillis(pollIntervalMs),
                Duration.ofMillis(maxWaitMs),
                millis -> Thread.sleep(millis));
    }

    AnalysisStreamer(
            RouteAnalysisService analysis,
            Duration pollInterval,
            Duration maxWait,
            Sleeper sleeper) {
        this.analysis = analysis;
        this.pollInterval = pollInterval;
        this.maxWait = maxWait;
        this.sleeper = sleeper;
    }

    public void stream(UUID analysisId, SseEmitter emitter) throws IOException {
        Set<Integer> sent = new HashSet<>();
        long waited = 0L;

        while (true) {
            List<AnalysisFrame> frames = analysis.frames(analysisId);
            if (frames.isEmpty()) {
                emitter.send(SseEmitter.event().name("no-imagery").data(
                        new StreamComplete(analysisId, 0, 0, "no_imagery")));
                emitter.complete();
                return;
            }

            for (AnalysisFrame frame : frames) {
                if (frame.scored() && sent.add(frame.seq())) {
                    emitter.send(SseEmitter.event().name("frame").data(frame));
                }
            }

            if (sent.size() == frames.size()) {
                analysis.refreshStatus(analysisId);
                emitter.send(SseEmitter.event().name("complete").data(
                        new StreamComplete(
                                analysisId, frames.size(), sent.size(), "ready")));
                emitter.complete();
                return;
            }

            if (waited >= maxWait.toMillis()) {
                // Partial results beat an error: the client keeps the frames it
                // has and can re-open the stream once the worker catches up.
                emitter.send(SseEmitter.event().name("timeout").data(
                        new StreamComplete(
                                analysisId, frames.size(), sent.size(), "pending")));
                emitter.complete();
                return;
            }

            try {
                sleeper.sleep(pollInterval.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                emitter.complete();
                return;
            }
            waited += pollInterval.toMillis();
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public record StreamComplete(
            UUID analysisId,
            int frameCount,
            int deliveredCount,
            String status) {
    }
}
