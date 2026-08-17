package com.beacon.api.audio;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Produces speech through the cache, falling back between providers.
 *
 * <p>Order matters: the cache is consulted before any provider, and providers
 * are tried in preference order so exhausted credits or an outage degrade to
 * the local voice rather than to silence.
 */
@Service
public class SpeechService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpeechService.class);

    private final List<TtsProvider> providers;
    private final SpeechCache cache;
    private final double speed;

    public SpeechService(List<TtsProvider> providers, SpeechCache cache, TtsProperties properties) {
        this.providers = providers;
        this.cache = cache;
        this.speed = properties.speed();
    }

    public boolean isAvailable() {
        return providers.stream().anyMatch(TtsProvider::isAvailable);
    }

    /**
     * Returns audio for a line, or empty when no provider can produce it.
     *
     * <p>Empty is a normal outcome rather than an error: a route manifest is
     * still useful with text and no audio, and the client falls back to the
     * device's own speech synthesis.
     */
    public Optional<CachedClip> speak(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }

        for (TtsProvider provider : providers) {
            if (!provider.isAvailable()) {
                continue;
            }
            String key = SpeechCache.cacheKey(text, provider.voiceId(), speed);
            Optional<SpeechClip> cached = cache.find(key, "audio/mpeg");
            if (cached.isPresent()) {
                return Optional.of(new CachedClip(key, cached.get(), true));
            }
            try {
                SpeechClip clip = provider.synthesize(text);
                cache.put(key, clip);
                return Optional.of(new CachedClip(key, clip, false));
            } catch (RuntimeException exception) {
                LOGGER.warn("Speech provider {} failed, trying the next: {}",
                        provider.getClass().getSimpleName(), exception.getMessage());
            }
        }
        return Optional.empty();
    }

    /** Looks up an already-synthesised clip by its key. */
    public Optional<SpeechClip> find(String key) {
        return cache.find(key, "audio/mpeg");
    }

    public record CachedClip(String key, SpeechClip clip, boolean fromCache) {
    }
}
