package com.beacon.api.routing;

import com.graphhopper.GHRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/routes")
@ConditionalOnProperty(prefix = "beacon.routing", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RouteController {

    private final RouteService routeService;
    private final RouteComparisonService comparisonService;

    public RouteController(RouteService routeService, RouteComparisonService comparisonService) {
        this.routeService = routeService;
        this.comparisonService = comparisonService;
    }

    @PostMapping
    public RouteResponse create(@Valid @RequestBody RouteRequest routeRequest) {
        validatePoint(routeRequest.origin(), "origin");
        validatePoint(routeRequest.destination(), "destination");
        GHRequest request = new GHRequest(
                routeRequest.origin().get(0),
                routeRequest.origin().get(1),
                routeRequest.destination().get(0),
                routeRequest.destination().get(1))
                .setProfile(routeRequest.mode().profile())
                .setLocale("en-US");
        routeRequest.variant().configure(request);
        return routeService.route(request);
    }

    @PostMapping("/compare")
    public RouteComparisonResponse compare(
            @Valid @RequestBody RouteComparisonRequest routeRequest
    ) {
        validatePoint(routeRequest.origin(), "origin");
        validatePoint(routeRequest.destination(), "destination");
        return comparisonService.compare(routeRequest);
    }

    private static void validatePoint(List<Double> point, String name) {
        double latitude = point.get(0);
        double longitude = point.get(1);
        if (!Double.isFinite(latitude) || latitude < -90.0 || latitude > 90.0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " latitude is out of range");
        }
        if (!Double.isFinite(longitude) || longitude < -180.0 || longitude > 180.0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " longitude is out of range");
        }
    }
}
