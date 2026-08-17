package com.beacon.api.analysis;

public enum AnalysisStatus {
    /** Frames are sampled; at least one still needs scoring by the worker. */
    PENDING("pending"),
    /** Every sampled frame has a stored analysis for the current model. */
    READY("ready"),
    /**
     * The route has no usable imagery. Mapillary coverage is strong in
     * Manhattan and along major corridors and thin elsewhere, so this is an
     * expected outcome rather than a failure.
     */
    NO_IMAGERY("no_imagery");

    private final String value;

    AnalysisStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
