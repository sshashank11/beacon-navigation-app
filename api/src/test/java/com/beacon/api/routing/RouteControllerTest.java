package com.beacon.api.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.graphhopper.GHRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class RouteControllerTest {

    @Test
    void createBuildsFootRequestFromLatitudeLongitudePairs() {
        RouteService routeService = mock(RouteService.class);
        RouteResponse expected = mock(RouteResponse.class);
        when(routeService.route(any(GHRequest.class))).thenReturn(expected);
        RouteController controller = new RouteController(
                routeService,
                mock(RouteComparisonService.class));

        assertThat(controller.create(new RouteRequest(
                List.of(40.7500, -73.9900),
                List.of(40.7600, -73.9800),
                RouteMode.FOOT))).isSameAs(expected);

        ArgumentCaptor<GHRequest> request = ArgumentCaptor.forClass(GHRequest.class);
        verify(routeService).route(request.capture());
        assertThat(request.getValue().getProfile()).isEqualTo("foot");
        assertThat(request.getValue().getPoints()).extracting("lat", "lon").containsExactly(
                org.assertj.core.groups.Tuple.tuple(40.7500, -73.9900),
                org.assertj.core.groups.Tuple.tuple(40.7600, -73.9800));
        assertThat(request.getValue().getCustomModel()).isNull();
    }

    @Test
    void createAppliesPm25ModelToCleanestVariant() {
        RouteService routeService = mock(RouteService.class);
        when(routeService.route(any(GHRequest.class))).thenReturn(mock(RouteResponse.class));
        RouteController controller = new RouteController(
                routeService,
                mock(RouteComparisonService.class));

        controller.create(new RouteRequest(
                List.of(40.7500, -73.9900),
                List.of(40.7600, -73.9800),
                RouteMode.FOOT,
                RouteVariant.CLEANEST));

        ArgumentCaptor<GHRequest> request = ArgumentCaptor.forClass(GHRequest.class);
        verify(routeService).route(request.capture());
        assertThat(request.getValue().getCustomModel().getPriority()).singleElement()
                .satisfies(statement -> {
                    assertThat(statement.condition()).isEqualTo("clw_pm25 > 70");
                    assertThat(statement.value()).isEqualTo("0.4");
                });
    }

    @Test
    void createRejectsCoordinatesOutsideGeographicBounds() {
        RouteController controller = new RouteController(
                mock(RouteService.class),
                mock(RouteComparisonService.class));

        assertThatThrownBy(() -> controller.create(new RouteRequest(
                List.of(91.0, -73.9900),
                List.of(40.7600, -73.9800),
                RouteMode.FOOT)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void compareDelegatesValidatedProfileRequest() {
        RouteComparisonService comparisonService = mock(RouteComparisonService.class);
        RouteComparisonResponse expected = mock(RouteComparisonResponse.class);
        RouteComparisonRequest request = new RouteComparisonRequest(
                List.of(40.7500, -73.9900),
                List.of(40.7600, -73.9800),
                RouteMode.FOOT,
                com.beacon.api.profiles.ProfilePreset.ASTHMA,
                null,
                null,
                null,
                null,
                null);
        when(comparisonService.compare(request)).thenReturn(expected);
        RouteController controller = new RouteController(
                mock(RouteService.class),
                comparisonService);

        assertThat(controller.compare(request)).isSameAs(expected);
        verify(comparisonService).compare(request);
    }
}
