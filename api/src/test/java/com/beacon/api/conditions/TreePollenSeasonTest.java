package com.beacon.api.conditions;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class TreePollenSeasonTest {

    @Test
    void followsTheLoadedAllergenicSpeciesSeason() {
        assertThat(TreePollenSeason.isActive(Instant.parse("2026-01-15T00:00:00Z"))).isFalse();
        assertThat(TreePollenSeason.isActive(Instant.parse("2026-03-15T00:00:00Z"))).isTrue();
        assertThat(TreePollenSeason.isActive(Instant.parse("2026-06-15T00:00:00Z"))).isTrue();
        assertThat(TreePollenSeason.isActive(Instant.parse("2026-07-15T00:00:00Z"))).isFalse();
    }
}
