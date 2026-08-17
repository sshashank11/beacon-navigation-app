package com.beacon.api.audio;

/** The provider could not produce audio this time. Callers may fall back. */
public class TtsUnavailableException extends RuntimeException {

    public TtsUnavailableException(String message) {
        super(message);
    }

    public TtsUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
