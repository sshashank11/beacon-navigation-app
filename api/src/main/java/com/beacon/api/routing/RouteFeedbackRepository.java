package com.beacon.api.routing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RouteFeedbackRepository {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate;

    public RouteFeedbackRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(
            UUID id,
            UUID routeId,
            boolean feltWorse,
            List<Integer> whichSegments,
            Instant createdAt
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO route_feedback
                  (id, route_id, felt_worse, which_segments, created_at)
                VALUES (?, ?, ?, CAST(? AS jsonb), ?)
                """,
                id,
                routeId,
                feltWorse,
                json(whichSegments),
                Timestamp.from(createdAt));
    }

    private String json(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize route feedback", exception);
        }
    }
}
