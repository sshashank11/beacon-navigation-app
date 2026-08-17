package com.beacon.api.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.beacon.api.routing.RouteHistoryRepository;
import com.beacon.api.routing.RouteNotFoundException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RouteAnalysisServiceTest {

    private static final String MODEL = "segformer-b0-cityscapes-1024-v1";

    private RouteHistoryRepository routes;
    private RouteImageSampler sampler;
    private RouteAnalysisRepository analyses;
    private AnalysisQueue queue;
    private RouteAnalysisService service;
    private UUID routeId;

    @BeforeEach
    void setUp() {
        routes = mock(RouteHistoryRepository.class);
        sampler = mock(RouteImageSampler.class);
        analyses = mock(RouteAnalysisRepository.class);
        queue = mock(AnalysisQueue.class);
        service = new RouteAnalysisService(routes, sampler, analyses, queue, MODEL);
        routeId = UUID.randomUUID();
        when(routes.exists(routeId)).thenReturn(true);
    }

    private SampledFrame frame(int seq, String id) {
        return new SampledFrame(seq, seq * 50.0, id, "https://img/" + id, 90.0, 4.2);
    }

    @Test
    void unknownRouteIsRejectedBeforeAnySamplingHappens() {
        UUID missing = UUID.randomUUID();
        when(routes.exists(missing)).thenReturn(false);

        assertThatThrownBy(() -> service.request(missing))
                .isInstanceOf(RouteNotFoundException.class);
        verify(sampler, never()).sample(any());
    }

    @Test
    void aRouteWithoutImageryIsRecordedRatherThanFailing() {
        when(sampler.sample(routeId)).thenReturn(List.of());

        RouteAnalysisService.AnalysisAccepted accepted = service.request(routeId);

        assertThat(accepted.status()).isEqualTo(AnalysisStatus.NO_IMAGERY);
        assertThat(accepted.frameCount()).isZero();
        verify(analyses).create(
                any(), eq(routeId), eq(AnalysisStatus.NO_IMAGERY), eq(0), eq(MODEL));
        verify(analyses, never()).saveFrames(any(), any());
        verify(queue, never()).enqueue(any());
    }

    @Test
    void unscoredFramesAreQueuedAndTheAnalysisStaysPending() {
        when(sampler.sample(routeId)).thenReturn(List.of(frame(0, "a"), frame(1, "b")));
        when(analyses.unscoredImageIds(any(), eq(MODEL))).thenReturn(List.of("b"));

        RouteAnalysisService.AnalysisAccepted accepted = service.request(routeId);

        assertThat(accepted.status()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(accepted.frameCount()).isEqualTo(2);
        assertThat(accepted.pendingCount()).isEqualTo(1);
        verify(queue).enqueue(List.of("b"));
    }

    @Test
    void anAlreadyScoredCorridorIsReadyImmediatelyAndQueuesNothing() {
        when(sampler.sample(routeId)).thenReturn(List.of(frame(0, "a")));
        when(analyses.unscoredImageIds(any(), eq(MODEL))).thenReturn(List.of());

        RouteAnalysisService.AnalysisAccepted accepted = service.request(routeId);

        assertThat(accepted.status()).isEqualTo(AnalysisStatus.READY);
        assertThat(accepted.pendingCount()).isZero();
        verify(analyses).updateStatus(accepted.analysisId(), AnalysisStatus.READY);
        verify(queue, never()).enqueue(any());
    }

    @Test
    void framesArePersistedBeforeTheQueueIsTold() {
        when(sampler.sample(routeId)).thenReturn(List.of(frame(0, "a")));
        when(analyses.unscoredImageIds(any(), eq(MODEL))).thenReturn(List.of("a"));

        service.request(routeId);

        var order = org.mockito.Mockito.inOrder(analyses, queue);
        order.verify(analyses).create(any(), any(), any(), anyInt(), anyString());
        order.verify(analyses).saveFrames(any(), any());
        order.verify(queue).enqueue(any());
    }

    @Test
    void missingAnalysisIsReportedAsNotFound() {
        UUID unknown = UUID.randomUUID();
        when(analyses.find(unknown)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.require(unknown))
                .isInstanceOf(AnalysisNotFoundException.class);
    }
}
