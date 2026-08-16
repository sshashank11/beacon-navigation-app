package com.beacon.api.routing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record RouteRequest(
        @NotNull @Size(min = 2, max = 2) List<@NotNull Double> origin,
        @NotNull @Size(min = 2, max = 2) List<@NotNull Double> destination,
        @NotNull @Valid RouteMode mode,
        RouteVariant variant
) {

    public RouteRequest {
        if (variant == null) {
            variant = RouteVariant.FASTEST;
        }
    }

    public RouteRequest(List<Double> origin, List<Double> destination, RouteMode mode) {
        this(origin, destination, mode, RouteVariant.FASTEST);
    }
}
