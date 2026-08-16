package com.beacon.api.routing;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RouteFeedbackResponse(
        UUID id,
        @JsonProperty("routeId") UUID routeId,
        @JsonProperty("feltWorse") boolean feltWorse,
        @JsonProperty("whichSegments") List<Integer> whichSegments,
        @JsonProperty("createdAt") Instant createdAt
) {
}
