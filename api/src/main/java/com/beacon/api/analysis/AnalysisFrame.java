package com.beacon.api.analysis;

/**
 * A sampled frame as sent to the client.
 *
 * <p>Metrics are null until the worker has scored the image under the current
 * model version. The viewer shows the thumbnail either way, so a frame arrives
 * as soon as it is known rather than waiting on inference.
 */
public record AnalysisFrame(
        int seq,
        double distanceOffsetM,
        String mapillaryId,
        String thumbUrl,
        double routeBearingDeg,
        double imageDistanceM,
        boolean scored,
        Double skyViewFactor,
        Double vegetationFrac,
        Double sidewalkFrac,
        Integer vehicleCount,
        Integer personCount) {

    public static AnalysisFrame unscored(
            int seq,
            double distanceOffsetM,
            String mapillaryId,
            String thumbUrl,
            double routeBearingDeg,
            double imageDistanceM) {
        return new AnalysisFrame(
                seq,
                distanceOffsetM,
                mapillaryId,
                thumbUrl,
                routeBearingDeg,
                imageDistanceM,
                false,
                null,
                null,
                null,
                null,
                null);
    }
}
