package com.beacon.api.hazards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HazardFieldServiceTest {

    @Test
    void currentAreasCreatesGraphHopperFeatureAndCachesDatabaseRows() {
        HazardFieldRepository fields = mock(HazardFieldRepository.class);
        HazardAreaCache cache = mock(HazardAreaCache.class);
        HazardFieldRow row = mock(HazardFieldRow.class);
        Instant observedAt = Instant.parse("2026-08-15T16:00:00Z");
        when(cache.get()).thenReturn(Optional.empty());
        when(fields.findLatestFieldRows()).thenReturn(List.of(row));
        when(row.getId()).thenReturn(7L);
        when(row.getHazard()).thenReturn("pm25");
        when(row.getSeverity()).thenReturn((short) 4);
        when(row.getBandMin()).thenReturn(35.0f);
        when(row.getBandMax()).thenReturn(80.0f);
        when(row.getObservedAt()).thenReturn(observedAt);
        when(row.getGeometryWkt()).thenReturn(
                "MULTIPOLYGON (((-74 40.7, -73.9 40.7, -73.9 40.8, -74 40.8, -74 40.7)))");

        HazardFieldService service = new HazardFieldService(fields, cache);

        assertThat(service.currentAreas()).singleElement().satisfies(area -> {
            assertThat(area.getId()).isEqualTo("pm25_severe");
            assertThat(area.getProperty("severity")).isEqualTo((short) 4);
            assertThat(area.getGeometry().isValid()).isTrue();
        });
        verify(cache).put(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.eq(Duration.ofMinutes(15)));
    }
}
