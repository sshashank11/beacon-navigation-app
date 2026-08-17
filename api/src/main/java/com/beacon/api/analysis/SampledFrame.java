package com.beacon.api.analysis;

/** One street image chosen for a point along a route. */
public record SampledFrame(
        int seq,
        double distanceOffsetM,
        String mapillaryId,
        String thumbUrl,
        double routeBearingDeg,
        double imageDistanceM) {
}
