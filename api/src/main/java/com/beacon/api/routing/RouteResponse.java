package com.beacon.api.routing;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public record RouteResponse(
        GeoJsonLineString geometry,
        @JsonProperty("distance_m") double distanceM,
        @JsonProperty("duration_s") double durationS,
        List<RouteInstruction> instructions
) {

    public record GeoJsonLineString(
            String type,
            List<List<Double>> coordinates
    ) {
    }

    public record RouteInstruction(
            int sign,
            @JsonProperty("street_name") String streetName,
            @JsonProperty("distance_m") double distanceM,
            @JsonProperty("duration_s") double durationS,
            List<List<Double>> points,
            Map<String, Object> extra
    ) {
    }
}
