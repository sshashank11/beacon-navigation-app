package com.beacon.api.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.ResponsePath;
import com.graphhopper.util.Instruction;
import com.graphhopper.util.InstructionList;
import com.graphhopper.util.PointList;
import org.junit.jupiter.api.Test;

class RouteServiceTest {

    @Test
    void routeReturnsGeoJsonMetricsAndInstructions() {
        GraphHopper hopper = mock(GraphHopper.class);
        GHRequest request = new GHRequest(40.75, -73.99, 40.76, -73.98).setProfile("foot");
        GHResponse graphResponse = new GHResponse();
        PointList routePoints = points(40.75, -73.99, 40.755, -73.992, 40.76, -73.98);
        PointList instructionPoints = points(40.75, -73.99, 40.755, -73.992);
        Instruction instruction = new Instruction(Instruction.TURN_LEFT, "Broadway", instructionPoints)
                .setDistance(310.5)
                .setTime(240_000);
        InstructionList instructions = new InstructionList(null);
        instructions.add(instruction);
        ResponsePath path = new ResponsePath()
                .setPoints(routePoints)
                .setDistance(925.4)
                .setTime(720_000);
        path.setInstructions(instructions);
        graphResponse.add(path);
        when(hopper.route(request)).thenReturn(graphResponse);

        RouteResponse response = new RouteService(hopper).route(request);

        assertThat(response.geometry().type()).isEqualTo("LineString");
        assertThat(response.geometry().coordinates()).containsExactly(
                java.util.List.of(-73.99, 40.75),
                java.util.List.of(-73.992, 40.755),
                java.util.List.of(-73.98, 40.76));
        assertThat(response.distanceM()).isEqualTo(925.4);
        assertThat(response.durationS()).isEqualTo(720.0);
        assertThat(response.instructions()).singleElement().satisfies(result -> {
            assertThat(result.sign()).isEqualTo(Instruction.TURN_LEFT);
            assertThat(result.streetName()).isEqualTo("Broadway");
            assertThat(result.durationS()).isEqualTo(240.0);
        });
    }

    @Test
    void routeTurnsGraphHopperErrorsIntoUnprocessableResponse() {
        GraphHopper hopper = mock(GraphHopper.class);
        GHRequest request = new GHRequest(40.75, -73.99, 40.76, -73.98).setProfile("foot");
        GHResponse graphResponse = new GHResponse().addError(new IllegalArgumentException("Point not found"));
        when(hopper.route(request)).thenReturn(graphResponse);

        assertThatThrownBy(() -> new RouteService(hopper).route(request))
                .isInstanceOf(RouteNotFoundException.class)
                .hasMessage("Point not found");
    }

    private static PointList points(double... coordinates) {
        PointList points = new PointList(coordinates.length / 2, false);
        for (int index = 0; index < coordinates.length; index += 2) {
            points.add(coordinates[index], coordinates[index + 1]);
        }
        return points;
    }
}
