package com.beacon.api.audio;

/** Synthesised speech and the format it came back in. */
public record SpeechClip(byte[] audio, String contentType) {

    public int sizeBytes() {
        return audio.length;
    }
}
