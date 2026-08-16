package com.beacon.api.profiles;

import com.beacon.api.hazards.Hazard;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Converter
public class HazardWeightsConverter implements AttributeConverter<Map<Hazard, Double>, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Double>> JSON_MAP = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(Map<Hazard, Double> weights) {
        Map<String, Double> values = new LinkedHashMap<>();
        if (weights != null) {
            weights.forEach((hazard, weight) -> values.put(hazard.key(), weight));
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Cannot serialize hazard weights", exception);
        }
    }

    @Override
    public Map<Hazard, Double> convertToEntityAttribute(String json) {
        Map<Hazard, Double> weights = new EnumMap<>(Hazard.class);
        if (json == null || json.isBlank()) {
            return weights;
        }
        try {
            OBJECT_MAPPER.readValue(json, JSON_MAP)
                    .forEach((hazard, weight) -> weights.put(Hazard.fromKey(hazard), weight));
            return weights;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Cannot deserialize hazard weights", exception);
        }
    }
}
