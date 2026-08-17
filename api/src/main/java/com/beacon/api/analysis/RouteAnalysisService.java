package com.beacon.api.analysis;

import com.beacon.api.routing.RouteHistoryRepository;
import com.beacon.api.routing.RouteNotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RouteAnalysisService {

    private final RouteHistoryRepository routes;
    private final RouteImageSampler sampler;
    private final RouteAnalysisRepository analyses;
    private final AnalysisQueue queue;
    private final String modelVersion;

    public RouteAnalysisService(
            RouteHistoryRepository routes,
            RouteImageSampler sampler,
            RouteAnalysisRepository analyses,
            AnalysisQueue queue,
            @Value("${beacon.imagery.model-version:segformer-b0-cityscapes-1024-v1}")
            String modelVersion) {
        this.routes = routes;
        this.sampler = sampler;
        this.analyses = analyses;
        this.queue = queue;
        this.modelVersion = modelVersion;
    }

    /**
     * Samples a saved route and queues whatever still needs scoring.
     *
     * <p>Returns immediately with an id the client can stream from; nothing
     * here waits on inference.
     */
    public AnalysisAccepted request(UUID routeId, UUID userId) {
        // Not found rather than forbidden: confirming that a route exists but
        // belongs to somebody else leaks more than it helps.
        if (!routes.isOwnedBy(routeId, userId)) {
            throw new RouteNotFoundException("Unknown route " + routeId);
        }

        UUID analysisId = UUID.randomUUID();
        List<SampledFrame> frames = sampler.sample(routeId);
        if (frames.isEmpty()) {
            analyses.create(
                    analysisId, routeId, AnalysisStatus.NO_IMAGERY, 0, modelVersion);
            return new AnalysisAccepted(
                    analysisId, routeId, AnalysisStatus.NO_IMAGERY, 0, 0);
        }

        List<String> pending;
        analyses.create(
                analysisId,
                routeId,
                AnalysisStatus.PENDING,
                frames.size(),
                modelVersion);
        analyses.saveFrames(analysisId, frames);
        pending = analyses.unscoredImageIds(analysisId, modelVersion);

        AnalysisStatus status =
                pending.isEmpty() ? AnalysisStatus.READY : AnalysisStatus.PENDING;
        if (status == AnalysisStatus.READY) {
            analyses.updateStatus(analysisId, status);
        } else {
            queue.enqueue(pending);
        }

        return new AnalysisAccepted(
                analysisId, routeId, status, frames.size(), pending.size());
    }

    public List<AnalysisFrame> frames(UUID analysisId) {
        return analyses.frames(analysisId, modelVersion);
    }

    public RouteAnalysisRepository.AnalysisSummary require(UUID analysisId, UUID userId) {
        RouteAnalysisRepository.AnalysisSummary summary = analyses.find(analysisId)
                .orElseThrow(() -> new AnalysisNotFoundException(analysisId));
        if (!routes.isOwnedBy(summary.routeId(), userId)) {
            throw new AnalysisNotFoundException(analysisId);
        }
        return summary;
    }

    /** Marks an analysis ready once every sampled frame has been scored. */
    public boolean refreshStatus(UUID analysisId) {
        boolean ready = analyses.unscoredImageIds(analysisId, modelVersion).isEmpty();
        if (ready) {
            analyses.updateStatus(analysisId, AnalysisStatus.READY);
        }
        return ready;
    }

    public record AnalysisAccepted(
            UUID analysisId,
            UUID routeId,
            AnalysisStatus status,
            int frameCount,
            int pendingCount) {
    }
}
