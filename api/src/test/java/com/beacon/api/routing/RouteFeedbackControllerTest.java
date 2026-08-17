package com.beacon.api.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RouteFeedbackControllerTest {

    @Test
    void delegatesValidatedFeedbackToTheService() {
        RouteFeedbackService feedback = mock(RouteFeedbackService.class);
        UUID routeId = UUID.randomUUID();
        RouteFeedbackRequest request = new RouteFeedbackRequest(true, List.of(1));
        RouteFeedbackResponse expected = mock(RouteFeedbackResponse.class);
        when(feedback.submit(routeId, null, request)).thenReturn(expected);

        com.beacon.api.users.CallerResolver callers =
                mock(com.beacon.api.users.CallerResolver.class);
        when(callers.require(null)).thenReturn(null);
        RouteFeedbackResponse response = new RouteFeedbackController(feedback, callers)
                .submit(routeId, request, null);

        assertThat(response).isSameAs(expected);
        verify(feedback).submit(routeId, null, request);
    }
}
