package com.beacon.api.audio;

import static org.assertj.core.api.Assumptions.assumeThat;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

/**
 * Talks to the real Fish Audio service when a key is present.
 *
 * <p>Skipped rather than failed without a key, so the suite stays runnable on
 * a machine that has none. This is the only way to find out whether the
 * hand-rolled MessagePack framing is actually what the service expects.
 */
class FishAudioLiveProbe {

    @Test
    void synthesisesAShortLine() {
        String key = System.getenv("FISH_AUDIO_KEY");
        assumeThat(key).as("FISH_AUDIO_KEY not set; skipping live probe").isNotBlank();

        TtsProperties properties = new TtsProperties(
                "fish", key, null, null, null, null, 1.0, "beacon");
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();

        try {
            SpeechClip clip = new FishAudioTtsProvider(client, properties)
                    .synthesize("Turn left onto Broadway.");
            assertThat(clip.sizeBytes()).isGreaterThan(1000);
            assertThat(clip.contentType()).isEqualTo("audio/mpeg");
        } catch (TtsUnavailableException exception) {
            // A key with no TTS credit answers 402 at the upgrade, before any
            // frame is sent. That is a billing state rather than a defect, and
            // it is exactly what the local fallback exists for, so treat it as
            // a skip. Note it also means the MessagePack framing below the
            // handshake stays unverified until the account has credit.
            assumeThat(exception.getMessage())
                    .as("reached Fish Audio but it declined: %s", exception.getMessage())
                    .doesNotContain("402");
            throw exception;
        }
    }
}
