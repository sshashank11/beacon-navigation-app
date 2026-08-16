package com.beacon.api.profiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.beacon.api.routing.RouteComparisonRequest;
import com.beacon.api.routing.RouteComparisonResponse;
import com.beacon.api.routing.RouteComparisonService;
import org.junit.jupiter.api.Test;

class ProfilePreviewControllerTest {

    @Test
    void previewDelegatesTheInProgressProfileToRouteComparison() {
        RouteComparisonService comparisons = mock(RouteComparisonService.class);
        RouteComparisonRequest request = mock(RouteComparisonRequest.class);
        RouteComparisonResponse expected = mock(RouteComparisonResponse.class);
        when(comparisons.preview(request)).thenReturn(expected);

        RouteComparisonResponse response = new ProfilePreviewController(comparisons)
                .preview(request);

        assertThat(response).isSameAs(expected);
        verify(comparisons).preview(request);
    }
}
