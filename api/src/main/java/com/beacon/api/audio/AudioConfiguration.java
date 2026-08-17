package com.beacon.api.audio;

import io.minio.MinioClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TtsProperties.class)
public class AudioConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(AudioConfiguration.class);

    @Bean
    OkHttpClient ttsHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    @Bean
    MinioClient minioClient(
            @Value("${beacon.storage.endpoint:http://localhost:9000}") String endpoint,
            @Value("${beacon.storage.access-key:beacon}") String accessKey,
            @Value("${beacon.storage.secret-key:beacon-password}") String secretKey) {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

    @Bean
    SpeechCache speechCache(MinioClient minio, TtsProperties properties) {
        return new SpeechCache(minio, properties.bucket());
    }

    /**
     * Providers in preference order.
     *
     * <p>"auto" prefers the hosted voice when a key is present and keeps the
     * local one behind it, so an outage mid-demo changes how it sounds rather
     * than whether it works.
     */
    @Bean
    List<TtsProvider> ttsProviders(OkHttpClient client, TtsProperties properties) {
        FishAudioTtsProvider fish = new FishAudioTtsProvider(client, properties);
        PiperTtsProvider piper = new PiperTtsProvider(properties);
        List<TtsProvider> ordered = new ArrayList<>();
        switch (properties.provider()) {
            case "fish" -> ordered.add(fish);
            case "piper" -> ordered.add(piper);
            case "none" -> { }
            default -> {
                ordered.add(fish);
                ordered.add(piper);
            }
        }
        LOGGER.info("Speech providers enabled: {}", ordered.stream()
                .map(provider -> provider.getClass().getSimpleName()
                        + (provider.isAvailable() ? " (ready)" : " (unconfigured)"))
                .toList());
        return ordered;
    }
}
