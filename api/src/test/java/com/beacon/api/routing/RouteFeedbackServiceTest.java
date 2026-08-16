package com.beacon.api.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class RouteFeedbackServiceTest {

    @Test
    void storesFeedbackForAnExistingRoute() {
        RouteHistoryRepository routes = mock(RouteHistoryRepository.class);
        RouteFeedbackRepository feedback = mock(RouteFeedbackRepository.class);
        UUID routeId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-16T20:00:00Z");
        when(routes.exists(routeId)).thenReturn(true);
        RouteFeedbackService service = new RouteFeedbackService(
                routes,
                feedback,
                Clock.fixed(now, ZoneOffset.UTC));

        RouteFeedbackResponse response = service.submit(
                routeId,
                new RouteFeedbackRequest(true, List.of(2, 3)));

        assertThat(response.routeId()).isEqualTo(routeId);
        assertThat(response.feltWorse()).isTrue();
        assertThat(response.whichSegments()).containsExactly(2, 3);
        assertThat(response.createdAt()).isEqualTo(now);
        verify(feedback).save(response.id(), routeId, true, List.of(2, 3), now);
    }

    @Test
    void rejectsFeedbackForAnUnknownRoute() {
        RouteHistoryRepository routes = mock(RouteHistoryRepository.class);
        RouteFeedbackRepository feedback = mock(RouteFeedbackRepository.class);
        UUID routeId = UUID.randomUUID();
        when(routes.exists(routeId)).thenReturn(false);
        RouteFeedbackService service = new RouteFeedbackService(
                routes,
                feedback,
                Clock.systemUTC());

        assertThatThrownBy(() -> service.submit(
                routeId,
                new RouteFeedbackRequest(false, List.of())))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(feedback);
    }
}
