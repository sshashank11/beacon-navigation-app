package com.beacon.api.hazards;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

public interface HazardAreaCache {
    Optional<List<HazardAreaCacheEntry>> get();

    void put(List<HazardAreaCacheEntry> areas, Duration ttl);
}
