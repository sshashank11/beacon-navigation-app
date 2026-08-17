package com.beacon.api.audio;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "beacon.tts")
public record TtsProperties(
        String provider,
        String fishApiKey,
        String fishReferenceId,
        String fishModel,
        String piperBinary,
        String piperVoice,
        Double speed,
        String bucket) {

    public TtsProperties {
        provider = provider == null || provider.isBlank() ? "auto" : provider;
        fishModel = fishModel == null || fishModel.isBlank() ? "speech-1.6" : fishModel;
        speed = speed == null ? 1.0 : speed;
        bucket = bucket == null || bucket.isBlank() ? "beacon" : bucket;
    }
}
