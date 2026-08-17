package com.beacon.api.audio;

/**
 * Turns a line of text into speech.
 *
 * <p>Implementations are interchangeable on purpose. A hosted voice sounds
 * better, but a credit balance running out mid-demo should degrade to a local
 * robotic voice rather than to silence, so the local implementation is the
 * fallback rather than an afterthought.
 */
public interface TtsProvider {

    SpeechClip synthesize(String text);

    /** Identifies the voice in cache keys, so changing voice invalidates them. */
    String voiceId();

    /** Whether this provider can actually run right now. */
    boolean isAvailable();
}
