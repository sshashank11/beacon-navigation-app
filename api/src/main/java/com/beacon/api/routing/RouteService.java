package com.beacon.api.routing;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.ResponsePath;
import com.graphhopper.util.Instruction;
import com.graphhopper.util.PointList;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.LineString;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "beacon.routing", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RouteService {

    private final GraphHopper hopper;

    public RouteService(GraphHopper hopper) {
        this.hopper = hopper;
    }

    public RouteResponse route(GHRequest request) {
        GHResponse response = hopper.route(request);
        if (response.hasErrors()) {
            String message = response.getErrors().stream()
                    .map(Throwable::getMessage)
                    .collect(Collectors.joining("; "));
            throw new RouteNotFoundException(message);
        }
        return toResponse(response.getBest());
    }

    private static RouteResponse toResponse(ResponsePath path) {
        LineString lineString = path.getPoints().toLineString(false);
        List<List<Double>> coordinates = new ArrayList<>(lineString.getNumPoints());
        for (var coordinate : lineString.getCoordinates()) {
            coordinates.add(List.of(coordinate.x, coordinate.y));
        }

        List<RouteResponse.RouteInstruction> instructions = path.getInstructions().stream()
                .map(RouteService::toInstruction)
                .toList();
        return new RouteResponse(
                new RouteResponse.GeoJsonLineString("LineString", coordinates),
                path.getDistance(),
                path.getTime() / 1000.0,
                instructions);
    }

    private static RouteResponse.RouteInstruction toInstruction(Instruction instruction) {
        return new RouteResponse.RouteInstruction(
                instruction.getSign(),
                instruction.getName(),
                instruction.getDistance(),
                instruction.getTime() / 1000.0,
                coordinates(instruction.getPoints()),
                instruction.getExtraInfoJSON());
    }

    private static List<List<Double>> coordinates(PointList points) {
        List<List<Double>> coordinates = new ArrayList<>(points.size());
        for (int index = 0; index < points.size(); index++) {
            coordinates.add(List.of(points.getLon(index), points.getLat(index)));
        }
        return coordinates;
    }
}
