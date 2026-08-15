package com.beacon.api.conditions;

import java.time.Instant;

public record AlertCondition(
        String id,
        String event,
        String headline,
        String severity,
        String urgency,
        Instant onset,
        Instant expiresAt
) {
}
