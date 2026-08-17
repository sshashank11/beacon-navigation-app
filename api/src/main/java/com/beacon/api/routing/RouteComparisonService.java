package com.beacon.api.routing;

import com.beacon.api.conditions.ConditionsService;
import com.beacon.api.conditions.SeasonalGates;
import com.beacon.api.profiles.CustomModelBuilder;
import com.beacon.api.profiles.TriggerProfile;
import com.graphhopper.GHRequest;
import com.graphhopper.ResponsePath;
import com.graphhopper.util.CustomModel;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "beacon.routing", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RouteComparisonService {

    private static final int MAX_ATTEMPTS = 3;

    private final RouteService routes;
    private final CustomModelBuilder models;
    private final ConditionsService conditions;
    private final RouteHistoryRepository history;

    public RouteComparisonService(
            RouteService routes,
            CustomModelBuilder models,
            ConditionsService conditions,
            RouteHistoryRepository history
    ) {
        this.routes = routes;
        this.models = models;
        this.conditions = conditions;
        this.history = history;
    }

    public RouteComparisonResponse compare(RouteComparisonRequest request) {
        return compare(request, (UUID) null);
    }

    /**
     * Compares route variants, recording them against an account when there
     * is one. An anonymous request still routes; it just leaves no history.
     */
    public RouteComparisonResponse compare(RouteComparisonRequest request, UUID userId) {
        return compare(request, true, userId);
    }

    public RouteComparisonResponse preview(RouteComparisonRequest request) {
        return compare(request, false, null);
    }

    private RouteComparisonResponse compare(
            RouteComparisonRequest request,
            boolean persist,
            UUID userId) {
        TriggerProfile profile = request.toProfile();
        SeasonalGates gates = conditions.seasonalGates();
        ResponsePath fastestPath = routes.routePath(request(request, null));
        double fastestDistance = fastestPath.getDistance();
        Map<String, Double> fastestExposure = RouteExposureCalculator.calculate(fastestPath);

        Attempt fastest = new Attempt(fastestPath, 0.0, 1, fastestDistance, false);
        Attempt balanced = routeWithinCap(
                request,
                profile,
                gates,
                1.0,
                fastestDistance * (1.0 + profile.getDetourTolerance()));
        Attempt cleanest = routeWithinCap(
                request,
                profile,
                gates,
                2.0,
                fastestDistance * (1.0 + profile.getDetourTolerance() * 2.0));

        RouteComparisonResponse.ComparedRoute fastestResponse = response(
                "fastest", fastest, fastestExposure, fastestDistance, persist, userId);
        RouteComparisonResponse.ComparedRoute balancedResponse = response(
                "balanced", balanced, fastestExposure, fastestDistance, persist, userId);
        RouteComparisonResponse.ComparedRoute cleanestResponse = response(
                "cleanest", cleanest, fastestExposure, fastestDistance, persist, userId);
        return new RouteComparisonResponse(fastestResponse, balancedResponse, cleanestResponse);
    }

    private Attempt routeWithinCap(
            RouteComparisonRequest request,
            TriggerProfile profile,
            SeasonalGates gates,
            double initialScale,
            double distanceCap
    ) {
        Attempt shortest = null;
        double scale = initialScale;
        for (int attemptNumber = 1; attemptNumber <= MAX_ATTEMPTS; attemptNumber++) {
            CustomModel model = models.build(profile, scale, gates);
            ResponsePath path = routes.routePath(request(request, model));
            Attempt attempt = new Attempt(path, scale, attemptNumber, distanceCap, false);
            if (shortest == null || path.getDistance() < shortest.path().getDistance()) {
                shortest = attempt;
            }
            if (path.getDistance() <= distanceCap) {
                return attempt;
            }
            scale /= 2.0;
        }
        return new Attempt(
                shortest.path(),
                shortest.weightScale(),
                MAX_ATTEMPTS,
                distanceCap,
                true);
    }

    private RouteComparisonResponse.ComparedRoute response(
            String variant,
            Attempt attempt,
            Map<String, Double> fastestExposure,
            double fastestDistance,
            boolean persist,
            UUID userId
    ) {
        Map<String, Double> exposure = RouteExposureCalculator.calculate(attempt.path());
        RouteResponse route = RouteService.toResponse(attempt.path());
        UUID id = persist ? UUID.randomUUID() : null;
        if (persist) {
            history.save(id, userId, variant, route, exposure);
        }
        return new RouteComparisonResponse.ComparedRoute(
                id,
                route,
                exposure,
                RouteExposureCalculator.comparativeDiff(
                        exposure,
                        fastestExposure,
                        attempt.path().getDistance(),
                        fastestDistance),
                attempt.weightScale(),
                attempt.attempts(),
                attempt.distanceCap(),
                attempt.detourCapExceeded());
    }

    private static GHRequest request(RouteComparisonRequest request, CustomModel model) {
        GHRequest graphRequest = new GHRequest(
                request.origin().get(0),
                request.origin().get(1),
                request.destination().get(0),
                request.destination().get(1))
                .setProfile(request.mode().profile())
                .setLocale("en-US")
                .setPathDetails(RouteExposureCalculator.pathDetails());
        if (model != null) {
            graphRequest.setCustomModel(model);
        }
        return graphRequest;
    }

    private record Attempt(
            ResponsePath path,
            double weightScale,
            int attempts,
            double distanceCap,
            boolean detourCapExceeded
    ) {
    }
}
