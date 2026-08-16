package com.beacon.api.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.beacon.api.routing.score.StaticScore;
import com.graphhopper.ResponsePath;
import com.graphhopper.util.PointList;
import com.graphhopper.util.details.PathDetail;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RouteExposureCalculatorTest {

    @Test
    void calculatesLengthWeightedMeansAndInvertsShade() {
        PointList points = new PointList(3, false);
        points.add(40.7500, -73.9900);
        points.add(40.7510, -73.9900);
        points.add(40.7520, -73.9900);
        ResponsePath path = new ResponsePath().setPoints(points);
        path.addPathDetails(Map.of(
                StaticScore.PM25.encodedValueName(), List.of(
                        detail(20, 0, 1),
                        detail(80, 1, 2)),
                StaticScore.SHADE.encodedValueName(), List.of(detail(70, 0, 2))));

        Map<String, Double> exposure = RouteExposureCalculator.calculate(path);

        assertThat(exposure.get("pm25")).isEqualTo(50.0);
        assertThat(exposure.get("shade_deficit")).isEqualTo(30.0);
    }

    @Test
    void reportsRelativeChangesAgainstFastest() {
        Map<String, Double> diff = RouteExposureCalculator.comparativeDiff(
                Map.of("pm25", 30.0, "grade", 0.0),
                Map.of("pm25", 50.0, "grade", 0.0),
                1_200.0,
                1_000.0);

        assertThat(diff).containsEntry("pm25", -0.4);
        assertThat(diff).containsEntry("grade", 0.0);
        assertThat(diff).containsEntry("distance", 0.2);
    }

    private static PathDetail detail(Number value, int first, int last) {
        PathDetail detail = new PathDetail(value);
        detail.setFirst(first);
        detail.setLast(last);
        return detail;
    }
}
