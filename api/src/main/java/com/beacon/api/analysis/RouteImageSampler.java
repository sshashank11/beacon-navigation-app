package com.beacon.api.analysis;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Walks a saved route and picks the street image that faces along it.
 *
 * <p>Sampling runs in PostGIS rather than Java: the route geometry, the image
 * points, and the spatial index all live there, so pulling the polyline out to
 * step through it in Java would mean a query per sample point.
 *
 * <p>A camera pointing across the street or back the way you came shows a
 * different scene than the one being walked, so candidates are restricted to
 * those whose compass angle sits within a tolerance of the route bearing.
 * Bearing is measured over a short span centred on each sample point, which
 * keeps a single jittery vertex from rotating it.
 */
@Repository
public class RouteImageSampler {

    public static final double SAMPLE_INTERVAL_M = 50.0;
    public static final double SEARCH_RADIUS_M = 30.0;
    public static final double BEARING_TOLERANCE_DEG = 45.0;
    private static final double BEARING_SPAN_M = 10.0;

    private static final String SAMPLE_QUERY = """
            WITH route_line AS (
              SELECT geom, ST_Length(geom::geography) AS length_m
              FROM route
              WHERE id = ?
            ),
            steps AS (
              SELECT
                step,
                step * ? AS distance_offset_m,
                LEAST(step * ? / NULLIF(length_m, 0), 1.0) AS fraction,
                GREATEST((step * ? - ?) / NULLIF(length_m, 0), 0.0) AS behind_fraction,
                LEAST((step * ? + ?) / NULLIF(length_m, 0), 1.0) AS ahead_fraction,
                geom
              FROM route_line,
                   generate_series(
                     0,
                     GREATEST(FLOOR(length_m / ?)::int, 0)
                   ) AS step
            ),
            points AS (
              SELECT
                step,
                distance_offset_m,
                ST_LineInterpolatePoint(geom, fraction) AS point,
                DEGREES(ST_Azimuth(
                  ST_LineInterpolatePoint(geom, behind_fraction),
                  ST_LineInterpolatePoint(geom, ahead_fraction)
                )) AS bearing_deg
              FROM steps
            ),
            matched AS (
              SELECT
                points.step,
                points.distance_offset_m,
                COALESCE(points.bearing_deg, 0) AS bearing_deg,
                image.mapillary_id,
                image.thumb_url,
                image.image_distance_m
              FROM points
              CROSS JOIN LATERAL (
                SELECT
                  candidate.mapillary_id,
                  candidate.thumb_url,
                  ST_Distance(
                    candidate.geom::geography,
                    points.point::geography
                  ) AS image_distance_m
                FROM street_image candidate
                WHERE ST_DWithin(
                        candidate.geom::geography,
                        points.point::geography,
                        ?
                      )
                  AND ABS(
                        ((candidate.compass_angle
                          - COALESCE(points.bearing_deg, 0) + 540)::numeric % 360)
                        - 180
                      ) <= ?
                ORDER BY candidate.geom <-> points.point
                LIMIT 1
              ) AS image
            ),
            deduplicated AS (
              SELECT
                matched.*,
                LAG(mapillary_id) OVER (ORDER BY step) AS previous_id
              FROM matched
            )
            SELECT distance_offset_m, bearing_deg, mapillary_id, thumb_url,
                   image_distance_m
            FROM deduplicated
            WHERE previous_id IS DISTINCT FROM mapillary_id
            ORDER BY distance_offset_m
            """;

    private final JdbcTemplate jdbcTemplate;

    public RouteImageSampler(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<SampledFrame> sample(UUID routeId) {
        List<SampledFrame> frames = jdbcTemplate.query(
                SAMPLE_QUERY,
                (resultSet, rowNumber) -> new SampledFrame(
                        rowNumber,
                        resultSet.getDouble("distance_offset_m"),
                        resultSet.getString("mapillary_id"),
                        resultSet.getString("thumb_url"),
                        resultSet.getDouble("bearing_deg"),
                        resultSet.getDouble("image_distance_m")),
                routeId,
                SAMPLE_INTERVAL_M,
                SAMPLE_INTERVAL_M,
                SAMPLE_INTERVAL_M,
                BEARING_SPAN_M,
                SAMPLE_INTERVAL_M,
                BEARING_SPAN_M,
                SAMPLE_INTERVAL_M,
                SEARCH_RADIUS_M,
                BEARING_TOLERANCE_DEG);
        return List.copyOf(frames);
    }
}
