package com.beacon.api.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.beacon.api.conditions.ConditionsService;
import com.beacon.api.conditions.SeasonalGates;
import com.beacon.api.profiles.CustomModelBuilder;
import com.beacon.api.profiles.ProfilePreset;
import com.beacon.api.profiles.TriggerProfile;
import com.beacon.api.routing.score.StaticScore;
import com.graphhopper.GHRequest;
import com.graphhopper.ResponsePath;
import com.graphhopper.util.CustomModel;
import com.graphhopper.util.InstructionList;
import com.graphhopper.util.PointList;
import com.graphhopper.util.details.PathDetail;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RouteComparisonServiceTest {

    @Test
    void retriesBothWeightedVariantsAndFlagsAnUnmetCleanestCap() {
        RouteService routes = mock(RouteService.class);
        CustomModelBuilder models = mock(CustomModelBuilder.class);
        ConditionsService conditions = mock(ConditionsService.class);
        RouteHistoryRepository history = mock(RouteHistoryRepository.class);
        when(conditions.seasonalGates()).thenReturn(new SeasonalGates(false, false, Set.of()));
        when(models.build(any(TriggerProfile.class), anyDouble(), any(SeasonalGates.class)))
                .thenReturn(new CustomModel());
        when(routes.routePath(any(GHRequest.class))).thenReturn(
                path(1_000, 50),
                path(1_300, 45),
                path(1_150, 42),
                path(1_080, 40),
                path(1_500, 35),
                path(1_300, 38),
                path(1_250, 39));
        RouteComparisonRequest request = new RouteComparisonRequest(
                List.of(40.75, -73.99),
                List.of(40.76, -73.98),
                RouteMode.FOOT,
                ProfilePreset.ASTHMA,
                null,
                null,
                null,
                0.1,
                null);

        RouteComparisonResponse response = new RouteComparisonService(routes, models, conditions, history)
                .compare(request);

        assertThat(response.fastest().route().distanceM()).isEqualTo(1_000);
        assertThat(response.fastest().id()).isNotNull();
        assertThat(response.fastest().comparativeDiff()).containsEntry("distance", 0.0);
        assertThat(response.balanced().route().distanceM()).isEqualTo(1_080);
        assertThat(response.balanced().attempts()).isEqualTo(3);
        assertThat(response.balanced().weightScale()).isEqualTo(0.25);
        assertThat(response.balanced().detourCapExceeded()).isFalse();
        assertThat(response.cleanest().route().distanceM()).isEqualTo(1_250);
        assertThat(response.cleanest().attempts()).isEqualTo(3);
        assertThat(response.cleanest().weightScale()).isEqualTo(0.5);
        assertThat(response.cleanest().detourCapM()).isEqualTo(1_200);
        assertThat(response.cleanest().detourCapExceeded()).isTrue();
        assertThat(response.cleanest().comparativeDiff()).containsEntry("pm25", -0.22);
        verify(conditions).seasonalGates();
        verify(history, times(3)).save(any(), any(), any(), any(), any());
    }

    private static ResponsePath path(double distance, int score) {
        PointList points = new PointList(2, false);
        points.add(40.75, -73.99);
        points.add(40.76, -73.98);
        ResponsePath path = new ResponsePath()
                .setPoints(points)
                .setDistance(distance)
                .setTime(600_000);
        path.setInstructions(new InstructionList(null));
        Map<String, List<PathDetail>> details = new LinkedHashMap<>();
        for (String encodedValue : RouteExposureCalculator.pathDetails()) {
            PathDetail detail = new PathDetail(score);
            detail.setFirst(0);
            detail.setLast(1);
            details.put(encodedValue, List.of(detail));
        }
        path.addPathDetails(details);
        return path;
    }
}
