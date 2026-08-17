package com.beacon.api.profiles;

import com.beacon.api.hazards.Hazard;
import com.beacon.api.routing.RouteMode;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A trigger profile as supplied with a request.
 *
 * <p>Deliberately not persisted. Self-reported sensitivities are the most
 * personal thing this product touches, and the routing engine only needs them
 * for the length of one request, so the client owns them and the server keeps
 * nothing. Validation still applies, because a malformed profile should be
 * rejected rather than silently clamped.
 */
public class TriggerProfile {

    private final UUID id = UUID.randomUUID();
    private UUID userId;

    @Size(max = 120)
    private String label;

    @NotNull
    private RouteMode mode;

    @NotNull
    private Map<
            @NotNull Hazard,
            @NotNull @DecimalMin("0.0") @DecimalMax("3.0") Double> weights = new EnumMap<>(Hazard.class);

    @NotNull
    private String[] hardAvoids = new String[0];

    @NotNull
    @DecimalMin("0.0")
    @DecimalMax("20.0")
    private Double maxGradePct;

    @NotNull
    @DecimalMin("0.0")
    @DecimalMax("2.0")
    private Double detourTolerance;

    @NotNull
    @DecimalMin(value = "0.0", inclusive = false)
    @DecimalMax("2.0")
    private Double conservatism;

    public TriggerProfile(
            UUID userId,
            String label,
            RouteMode mode,
            Map<Hazard, Double> weights,
            Set<HardAvoid> hardAvoids,
            Double maxGradePct,
            Double detourTolerance,
            Double conservatism) {
        this.userId = userId;
        this.label = label;
        this.mode = mode;
        this.weights = copyWeights(weights);
        this.hardAvoids = hardAvoids == null
                ? null
                : hardAvoids.stream().map(HardAvoid::key).sorted().toArray(String[]::new);
        this.maxGradePct = maxGradePct;
        this.detourTolerance = detourTolerance;
        this.conservatism = conservatism;
    }

    @AssertTrue(message = "weights must contain only finite values")
    public boolean hasFiniteWeights() {
        return weights == null || weights.values().stream()
                .allMatch(weight -> weight != null && Double.isFinite(weight));
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getLabel() {
        return label;
    }

    public RouteMode getMode() {
        return mode;
    }

    public Map<Hazard, Double> getWeights() {
        return weights == null ? null : Map.copyOf(weights);
    }

    public Set<HardAvoid> getHardAvoids() {
        if (hardAvoids == null) {
            return null;
        }
        return Arrays.stream(hardAvoids)
                .map(HardAvoid::fromKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    public Double getMaxGradePct() {
        return maxGradePct;
    }

    public Double getDetourTolerance() {
        return detourTolerance;
    }

    public Double getConservatism() {
        return conservatism;
    }

    private static Map<Hazard, Double> copyWeights(Map<Hazard, Double> weights) {
        if (weights == null) {
            return null;
        }
        Map<Hazard, Double> copy = new EnumMap<>(Hazard.class);
        copy.putAll(weights);
        return copy;
    }
}
