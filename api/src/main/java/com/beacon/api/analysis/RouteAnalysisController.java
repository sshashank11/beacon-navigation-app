package com.beacon.api.analysis;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "beacon.routing", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RouteAnalysisController {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(RouteAnalysisController.class);
    private static final long STREAM_TIMEOUT_MS = 120_000L;

    private final RouteAnalysisService analysis;
    private final AnalysisStreamer streamer;
    private final ExecutorService streamExecutor;
    private final com.beacon.api.users.CallerResolver callers;

    public RouteAnalysisController(
            RouteAnalysisService analysis,
            AnalysisStreamer streamer,
            ExecutorService analysisStreamExecutor,
            com.beacon.api.users.CallerResolver callers) {
        this.analysis = analysis;
        this.streamer = streamer;
        this.streamExecutor = analysisStreamExecutor;
        this.callers = callers;
    }

    @PostMapping("/routes/{routeId}/analysis")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RouteAnalysisService.AnalysisAccepted request(
            @PathVariable UUID routeId,
            java.security.Principal principal) {
        return analysis.request(routeId, callers.require(principal));
    }

    @GetMapping("/analysis/{analysisId}")
    public AnalysisSnapshot snapshot(
            @PathVariable UUID analysisId,
            java.security.Principal principal) {
        RouteAnalysisRepository.AnalysisSummary summary =
                analysis.require(analysisId, callers.require(principal));
        List<AnalysisFrame> frames = analysis.frames(analysisId);
        return new AnalysisSnapshot(
                summary.id(),
                summary.routeId(),
                summary.status(),
                summary.frameCount(),
                frames);
    }

    /**
     * Streams frames as they are scored so the viewer fills progressively
     * instead of blocking on the whole batch.
     */
    @GetMapping(path = "/analysis/{analysisId}/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @PathVariable UUID analysisId,
            java.security.Principal principal) {
        analysis.require(analysisId, callers.require(principal));
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        streamExecutor.execute(() -> {
            try {
                streamer.stream(analysisId, emitter);
            } catch (IOException | RuntimeException exception) {
                LOGGER.debug("Analysis stream {} ended early", analysisId, exception);
                emitter.completeWithError(exception);
            }
        });
        return emitter;
    }

    public record AnalysisSnapshot(
            UUID analysisId,
            UUID routeId,
            String status,
            int frameCount,
            List<AnalysisFrame> frames) {
    }
}
