package com.beacon.api.audio;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SpeechCacheTest {

    @Test
    void theSameLineInTheSameVoiceReusesOneKey() {
        // This is the property that makes a metered voice API affordable.
        assertThat(SpeechCache.cacheKey("Turn left onto Broadway.", "fish:v1", 1.0))
                .isEqualTo(SpeechCache.cacheKey("Turn left onto Broadway.", "fish:v1", 1.0));
    }

    @Test
    void trivialFormattingDifferencesStillHit() {
        String canonical = SpeechCache.cacheKey("Turn left onto Broadway.", "fish:v1", 1.0);

        assertThat(SpeechCache.cacheKey("  Turn left onto Broadway.  ", "fish:v1", 1.0))
                .isEqualTo(canonical);
        assertThat(SpeechCache.cacheKey("Turn  left   onto Broadway.", "fish:v1", 1.0))
                .isEqualTo(canonical);
        assertThat(SpeechCache.cacheKey("turn left onto broadway.", "fish:v1", 1.0))
                .isEqualTo(canonical);
    }

    @Test
    void voiceAndSpeedChangesInvalidate() {
        String base = SpeechCache.cacheKey("Turn left.", "fish:v1", 1.0);

        assertThat(SpeechCache.cacheKey("Turn left.", "piper:en", 1.0)).isNotEqualTo(base);
        assertThat(SpeechCache.cacheKey("Turn left.", "fish:v1", 1.25)).isNotEqualTo(base);
    }

    @Test
    void differentLinesDoNotCollide() {
        assertThat(SpeechCache.cacheKey("Turn left.", "fish:v1", 1.0))
                .isNotEqualTo(SpeechCache.cacheKey("Turn right.", "fish:v1", 1.0));
    }

    @Test
    void keysAreHexSha256() {
        assertThat(SpeechCache.cacheKey("anything", "fish:v1", 1.0)).matches("[0-9a-f]{64}");
    }
}
