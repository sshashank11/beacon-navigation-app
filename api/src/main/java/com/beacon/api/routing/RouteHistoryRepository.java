package com.beacon.api.routing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RouteHistoryRepository {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String INSERT_ROUTE = """
            INSERT INTO route
              (id, user_id, variant, geom, distance_m, duration_s, exposure_breakdown,
               instructions, computed_at)
            VALUES (
              ?,
              ?,
              ?,
              ST_SetSRID(ST_GeomFromGeoJSON(?), 4326),
              ?,
              ?,
              CAST(? AS jsonb),
              CAST(? AS jsonb),
              now()
            )
            """;

    private final JdbcTemplate jdbcTemplate;

    public RouteHistoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(
            UUID id,
            UUID userId,
            String variant,
            RouteResponse route,
            Map<String, Double> exposureBreakdown
    ) {
        jdbcTemplate.update(
                INSERT_ROUTE,
                id,
                userId,
                variant,
                json(route.geometry()),
                route.distanceM(),
                route.durationS(),
                json(exposureBreakdown),
                json(route.instructions()));
    }

    public boolean exists(UUID id) {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM route WHERE id = ?)",
                Boolean.class,
                id);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * True when the account owns the route.
     *
     * <p>A route saved before signing in has no owner, so nobody can read it
     * back. Knowing a UUID is not the same as being entitled to the record.
     */
    public boolean isOwnedBy(UUID routeId, UUID userId) {
        Boolean owned = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM route WHERE id = ? AND user_id = ?)",
                Boolean.class,
                routeId,
                userId);
        return Boolean.TRUE.equals(owned);
    }

    private String json(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize route history", exception);
        }
    }
}
