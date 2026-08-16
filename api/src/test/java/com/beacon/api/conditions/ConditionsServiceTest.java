package com.beacon.api.conditions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConditionsServiceTest {

    @Test
    void currentSnapshotAveragesLatestStationReadings() {
        CitywideReadingRepository readings = mock(CitywideReadingRepository.class);
        NwsAlertRepository alerts = mock(NwsAlertRepository.class);
        Instant now = Instant.parse("2026-04-15T16:00:00Z");
        ConditionsService conditionsService = new ConditionsService(
                readings,
                alerts,
                Clock.fixed(now, ZoneOffset.UTC));
        when(readings.findLatestPerStation(24)).thenReturn(List.of(
                new CitywideReading("pm25", "station-a", now, "openaq", 14.0f, "ug/m3"),
                new CitywideReading("pm25", "station-b", now.minusSeconds(60), "openaq", 6.0f, "ug/m3"),
                new CitywideReading("pm25_aqi", "airnow:nyc", now, "airnow", 72.0f, "AQI"),
                new CitywideReading("pollen_tree", "pollen:a", now, "google_pollen", 4.0f, "UPI"),
                new CitywideReading("heat", "nws:nyc", now, "nws", 29.0f, "C")
        ));
        when(alerts.findActive()).thenReturn(List.of());

        ConditionSnapshot snapshot = conditionsService.currentSnapshot();

        HazardCondition pm25 = snapshot.hazards().stream()
                .filter(condition -> condition.hazard().equals("pm25"))
                .findFirst()
                .orElseThrow();
        assertThat(pm25.hazard()).isEqualTo("pm25");
        assertThat(pm25.meanValue()).isEqualTo(10.0);
        assertThat(pm25.stationCount()).isEqualTo(2);
        assertThat(snapshot.airQuality().getFirst().category()).isEqualTo("Moderate");
        assertThat(snapshot.pollen().treeUpi()).isEqualTo(4.0);
        assertThat(snapshot.weather().temperatureC()).isEqualTo(29.0);
        assertThat(snapshot.summary()).contains("PM25 AQI 72", "Tree pollen is high");
        assertThat(conditionsService.seasonalGates().shadeActive()).isTrue();
        assertThat(conditionsService.seasonalGates().treePollenSeasonActive()).isTrue();
        assertThat(conditionsService.seasonalGates().activePollenHazards()).contains("pollen_tree");
    }
}
