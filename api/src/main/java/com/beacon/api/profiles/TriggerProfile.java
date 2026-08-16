package com.beacon.api.profiles;

import com.beacon.api.hazards.Hazard;
import com.beacon.api.routing.RouteMode;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "trigger_profile")
public class TriggerProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Size(max = 120)
    private String label;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RouteMode mode;

    @NotNull
    @Convert(converter = HazardWeightsConverter.class)
    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private Map<
            @NotNull Hazard,
            @NotNull @DecimalMin("0.0") @DecimalMax("3.0") Double> weights = new EnumMap<>(Hazard.class);

    @NotNull
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "hard_avoids", nullable = false, columnDefinition = "text[]")
    private String[] hardAvoids = new String[0];

    @NotNull
    @DecimalMin("0.0")
    @DecimalMax("20.0")
    @Column(name = "max_grade_pct", nullable = false)
    private Double maxGradePct;

    @NotNull
    @DecimalMin("0.0")
    @DecimalMax("2.0")
    @Column(name = "detour_tolerance", nullable = false)
    private Double detourTolerance;

    @NotNull
    @DecimalMin(value = "0.0", inclusive = false)
    @DecimalMax("2.0")
    @Column(nullable = false)
    private Double conservatism;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TriggerProfile() {
    }

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

    @PrePersist
    void createTimestamps() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void updateTimestamp() {
        updatedAt = Instant.now();
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
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
