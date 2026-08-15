package com.beacon.api.conditions;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "nws_alert")
public class NwsAlert {

    @Id
    private String id;

    @Column(nullable = false)
    private String event;

    private String headline;
    private String severity;
    private String urgency;
    private String certainty;
    private Instant onset;

    @Column(name = "expires_at")
    private Instant expiresAt;

    private String description;
    private String instruction;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected NwsAlert() {
    }

    public NwsAlert(
            String id,
            String event,
            String headline,
            String severity,
            String urgency,
            Instant onset,
            Instant expiresAt,
            Instant updatedAt
    ) {
        this.id = id;
        this.event = event;
        this.headline = headline;
        this.severity = severity;
        this.urgency = urgency;
        this.onset = onset;
        this.expiresAt = expiresAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public String getEvent() {
        return event;
    }

    public String getHeadline() {
        return headline;
    }

    public String getSeverity() {
        return severity;
    }

    public String getUrgency() {
        return urgency;
    }

    public Instant getOnset() {
        return onset;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
