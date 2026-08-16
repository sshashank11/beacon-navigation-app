package com.beacon.api.routing;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record RouteComparisonResponse(
        ComparedRoute fastest,
        ComparedRoute balanced,
        ComparedRoute cleanest
) {

    public record ComparedRoute(
            RouteResponse route,
            @JsonProperty("exposure_breakdown") Map<String, Double> exposureBreakdown,
            @JsonProperty("comparative_diff") Map<String, Double> comparativeDiff,
            @JsonProperty("weight_scale") double weightScale,
            int attempts,
            @JsonProperty("detour_cap_m") double detourCapM,
            @JsonProperty("detour_cap_exceeded") boolean detourCapExceeded
    ) {
    }
}
