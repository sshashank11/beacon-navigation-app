package com.beacon.api.hazards;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisHazardAreaCache implements HazardAreaCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisHazardAreaCache.class);
    private static final String CACHE_KEY = "beacon:hazard-fields:current";

    private final RedisTemplate<String, List<HazardAreaCacheEntry>> redis;

    public RedisHazardAreaCache(RedisTemplate<String, List<HazardAreaCacheEntry>> redis) {
        this.redis = redis;
    }

    @Override
    public Optional<List<HazardAreaCacheEntry>> get() {
        try {
            return Optional.ofNullable(redis.opsForValue().get(CACHE_KEY));
        } catch (RuntimeException exception) {
            LOGGER.warn("Hazard area Redis read failed; falling back to Postgres", exception);
            return Optional.empty();
        }
    }

    @Override
    public void put(List<HazardAreaCacheEntry> areas, Duration ttl) {
        try {
            redis.opsForValue().set(CACHE_KEY, areas, ttl);
        } catch (RuntimeException exception) {
            LOGGER.warn("Hazard area Redis write failed; continuing without cache", exception);
        }
    }
}
