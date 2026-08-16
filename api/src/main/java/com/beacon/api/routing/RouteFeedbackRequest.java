package com.beacon.api.routing;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

public record RouteFeedbackRequest(
        @JsonProperty("feltWorse") @NotNull Boolean feltWorse,
        @JsonProperty("whichSegments") @NotNull @Size(max = 20)
        List<@PositiveOrZero Integer> whichSegments
) {

    public RouteFeedbackRequest {
        whichSegments = whichSegments == null ? List.of() : List.copyOf(whichSegments);
    }
}
