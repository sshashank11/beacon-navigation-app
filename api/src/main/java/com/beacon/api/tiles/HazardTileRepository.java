package com.beacon.api.tiles;

import java.util.EnumMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class HazardTileRepository {

    private static final int MIN_ZOOM = 0;
    private static final int MAX_ZOOM = 22;
    private static final String TILE_QUERY = """
            WITH bounds AS (
              SELECT
                tile_envelope AS web_mercator,
                ST_Transform(tile_envelope, 4326) AS wgs84
              FROM (SELECT ST_TileEnvelope(?, ?, ?) AS tile_envelope) AS tile
            ),
            tile_features AS (
              SELECT
                round(score.%1$s)::smallint AS score,
                ST_AsMVTGeom(
                  ST_Transform(segment.geom, 3857),
                  bounds.web_mercator,
                  4096,
                  64,
                  true
                ) AS geom
              FROM segment
              JOIN segment_static_score AS score ON score.segment_id = segment.id
              CROSS JOIN bounds
              WHERE score.%1$s IS NOT NULL
                AND segment.geom && bounds.wgs84
                AND ST_Intersects(segment.geom, bounds.wgs84)
            )
            SELECT ST_AsMVT(tile_features, 'hazard', 4096, 'geom')
            FROM tile_features
            WHERE geom IS NOT NULL
            """;
    private static final Map<HazardTile, String> TILE_QUERIES = tileQueries();

    private final JdbcTemplate jdbcTemplate;

    public HazardTileRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public byte[] tile(HazardTile hazard, int zoom, int x, int y) {
        validateCoordinates(zoom, x, y);
        byte[] tile = jdbcTemplate.queryForObject(
                TILE_QUERIES.get(hazard),
                byte[].class,
                zoom,
                x,
                y);
        return tile == null ? new byte[0] : tile;
    }

    static void validateCoordinates(int zoom, int x, int y) {
        if (zoom < MIN_ZOOM || zoom > MAX_ZOOM) {
            throw new IllegalArgumentException("Tile zoom must be between 0 and 22");
        }
        long dimension = 1L << zoom;
        if (x < 0 || y < 0 || x >= dimension || y >= dimension) {
            throw new IllegalArgumentException("Tile coordinates are outside zoom " + zoom);
        }
    }

    private static Map<HazardTile, String> tileQueries() {
        Map<HazardTile, String> queries = new EnumMap<>(HazardTile.class);
        for (HazardTile hazard : HazardTile.values()) {
            queries.put(hazard, TILE_QUERY.formatted(hazard.scoreColumn()));
        }
        return Map.copyOf(queries);
    }
}
