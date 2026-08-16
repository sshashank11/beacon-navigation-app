package com.beacon.api.routing;

import com.beacon.api.conditions.ConditionsService;
import com.beacon.api.conditions.SeasonalGates;
import com.beacon.api.profiles.CustomModelBuilder;
import com.beacon.api.profiles.TriggerProfile;
import com.graphhopper.GHRequest;
import com.graphhopper.ResponsePath;
import com.graphhopper.util.CustomModel;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "beacon.routing", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RouteComparisonService {

    private static final int MAX_ATTEMPTS = 3;

    private final RouteService routes;
    private final CustomModelBuilder models;
    private final ConditionsService conditions;

    public RouteComparisonService(
            RouteService routes,
            CustomModelBuilder models,
            ConditionsService conditions
    ) {
        this.routes = routes;
        this.models = models;
        this.conditions = conditions;
    }

    public RouteComparisonResponse compare(RouteComparisonRequest request) {
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

        return new RouteComparisonResponse(
                response(fastest, fastestExposure, fastestDistance),
                response(balanced, fastestExposure, fastestDistance),
                response(cleanest, fastestExposure, fastestDistance));
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

    private static RouteComparisonResponse.ComparedRoute response(
            Attempt attempt,
            Map<String, Double> fastestExposure,
            double fastestDistance
    ) {
        Map<String, Double> exposure = RouteExposureCalculator.calculate(attempt.path());
        return new RouteComparisonResponse.ComparedRoute(
                RouteService.toResponse(attempt.path()),
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
