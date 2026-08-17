package com.beacon.api.routing;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RouteFeedbackService {

    private final RouteHistoryRepository routes;
    private final RouteFeedbackRepository feedback;
    private final Clock clock;

    @Autowired
    public RouteFeedbackService(
            RouteHistoryRepository routes,
            RouteFeedbackRepository feedback
    ) {
        this(routes, feedback, Clock.systemUTC());
    }

    RouteFeedbackService(
            RouteHistoryRepository routes,
            RouteFeedbackRepository feedback,
            Clock clock
    ) {
        this.routes = routes;
        this.feedback = feedback;
        this.clock = clock;
    }

    public RouteFeedbackResponse submit(
            UUID routeId,
            UUID userId,
            RouteFeedbackRequest request) {
        if (!routes.isOwnedBy(routeId, userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Route was not found");
        }
        UUID id = UUID.randomUUID();
        Instant createdAt = clock.instant();
        feedback.save(id, routeId, request.feltWorse(), request.whichSegments(), createdAt);
        return new RouteFeedbackResponse(
                id,
                routeId,
                request.feltWorse(),
                request.whichSegments(),
                createdAt);
    }
}
