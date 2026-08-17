package com.beacon.api.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class AnalysisStreamerTest {

    private static AnalysisFrame scored(int seq) {
        return new AnalysisFrame(
                seq, seq * 50.0, "img-" + seq, "https://img/" + seq, 90.0, 3.0,
                true, 0.2, 0.1, 0.05, 2, 1);
    }

    private static AnalysisFrame pending(int seq) {
        return AnalysisFrame.unscored(seq, seq * 50.0, "img-" + seq, "u", 90.0, 3.0);
    }

    private AnalysisStreamer streamer(RouteAnalysisService service, List<Long> slept) {
        return new AnalysisStreamer(
                service,
                Duration.ofMillis(10),
                Duration.ofMillis(50),
                slept::add);
    }

    @Test
    void alreadyScoredFramesGoOutOnTheFirstPass() throws IOException {
        UUID id = UUID.randomUUID();
        RouteAnalysisService service = mock(RouteAnalysisService.class);
        when(service.frames(id)).thenReturn(List.of(scored(0), scored(1)));
        SseEmitter emitter = mock(SseEmitter.class);
        List<Long> slept = new ArrayList<>();

        streamer(service, slept).stream(id, emitter);

        verify(emitter, org.mockito.Mockito.times(3))
                .send(org.mockito.ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
        assertThat(slept).as("no waiting when everything is scored").isEmpty();
    }

    @Test
    void framesStreamAsTheWorkerScoresThem() throws IOException {
        UUID id = UUID.randomUUID();
        RouteAnalysisService service = mock(RouteAnalysisService.class);
        when(service.frames(id))
                .thenReturn(List.of(scored(0), pending(1)))
                .thenReturn(List.of(scored(0), scored(1)));
        SseEmitter emitter = mock(SseEmitter.class);
        List<Long> slept = new ArrayList<>();

        streamer(service, slept).stream(id, emitter);

        // Two frames plus the completion event, each frame sent exactly once.
        verify(emitter, org.mockito.Mockito.times(3))
                .send(org.mockito.ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
        assertThat(slept).containsExactly(10L);
        verify(service).refreshStatus(id);
    }

    @Test
    void aStalledWorkerEndsTheStreamWithPartialResults() throws IOException {
        UUID id = UUID.randomUUID();
        RouteAnalysisService service = mock(RouteAnalysisService.class);
        when(service.frames(id)).thenReturn(List.of(scored(0), pending(1)));
        SseEmitter emitter = mock(SseEmitter.class);
        List<Long> slept = new ArrayList<>();

        streamer(service, slept).stream(id, emitter);

        verify(emitter).complete();
        assertThat(slept).as("polls until the deadline, then gives up").hasSize(5);
    }

    @Test
    void aRouteWithoutImageryCompletesImmediately() throws IOException {
        UUID id = UUID.randomUUID();
        RouteAnalysisService service = mock(RouteAnalysisService.class);
        when(service.frames(id)).thenReturn(List.of());
        SseEmitter emitter = mock(SseEmitter.class);
        List<Long> slept = new ArrayList<>();

        streamer(service, slept).stream(id, emitter);

        verify(emitter).complete();
        assertThat(slept).isEmpty();
    }
}
