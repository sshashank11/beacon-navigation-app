package com.beacon.api.profiles;

import static org.assertj.core.api.Assertions.assertThat;

import com.beacon.api.hazards.Hazard;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HazardWeightsConverterTest {

    @Test
    void roundTripsCanonicalHazardKeys() {
        HazardWeightsConverter converter = new HazardWeightsConverter();

        String json = converter.convertToDatabaseColumn(Map.of(
                Hazard.PM25, 3.0,
                Hazard.TRAFFIC_PROX, 1.5));

        assertThat(json).contains("\"pm25\":3.0", "\"traffic_prox\":1.5");
        assertThat(converter.convertToEntityAttribute(json)).containsExactlyInAnyOrderEntriesOf(Map.of(
                Hazard.PM25, 3.0,
                Hazard.TRAFFIC_PROX, 1.5));
    }
}
