package com.beacon.api.analysis;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RouteAnalysisRepository {

    private static final String FRAME_QUERY = """
            SELECT
              frame.seq,
              frame.distance_offset_m,
              frame.mapillary_id,
              image.thumb_url,
              ST_X(image.geom) AS longitude,
              ST_Y(image.geom) AS latitude,
              frame.route_bearing_deg,
              frame.image_distance_m,
              analysis.sky_view_factor,
              analysis.vegetation_frac,
              analysis.sidewalk_frac,
              analysis.vehicle_count,
              analysis.person_count
            FROM route_analysis_frame frame
            JOIN street_image image ON image.mapillary_id = frame.mapillary_id
            LEFT JOIN image_analysis analysis
              ON analysis.mapillary_id = frame.mapillary_id
             AND analysis.model_version = ?
            WHERE frame.analysis_id = ?
            ORDER BY frame.seq
            """;

    private final JdbcTemplate jdbcTemplate;

    public RouteAnalysisRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void create(
            UUID analysisId,
            UUID routeId,
            AnalysisStatus status,
            int frameCount,
            String modelVersion) {
        jdbcTemplate.update(
                """
                INSERT INTO route_analysis
                  (id, route_id, status, frame_count, model_version)
                VALUES (?, ?, ?, ?, ?)
                """,
                analysisId,
                routeId,
                status.value(),
                frameCount,
                modelVersion);
    }

    public void saveFrames(UUID analysisId, List<SampledFrame> frames) {
        jdbcTemplate.batchUpdate(
                """
                INSERT INTO route_analysis_frame
                  (analysis_id, seq, distance_offset_m, mapillary_id,
                   route_bearing_deg, image_distance_m)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                frames.stream()
                        .map(frame -> new Object[] {
                                analysisId,
                                frame.seq(),
                                frame.distanceOffsetM(),
                                frame.mapillaryId(),
                                frame.routeBearingDeg(),
                                frame.imageDistanceM()})
                        .toList());
    }

    public List<AnalysisFrame> frames(UUID analysisId, String modelVersion) {
        return jdbcTemplate.query(
                FRAME_QUERY,
                (resultSet, rowNumber) -> {
                    Double skyViewFactor = (Double) resultSet.getObject("sky_view_factor");
                    return new AnalysisFrame(
                            resultSet.getInt("seq"),
                            resultSet.getDouble("distance_offset_m"),
                            resultSet.getString("mapillary_id"),
                            resultSet.getString("thumb_url"),
                            resultSet.getDouble("longitude"),
                            resultSet.getDouble("latitude"),
                            resultSet.getDouble("route_bearing_deg"),
                            resultSet.getDouble("image_distance_m"),
                            skyViewFactor != null,
                            skyViewFactor,
                            (Double) resultSet.getObject("vegetation_frac"),
                            (Double) resultSet.getObject("sidewalk_frac"),
                            (Integer) resultSet.getObject("vehicle_count"),
                            (Integer) resultSet.getObject("person_count"));
                },
                modelVersion,
                analysisId);
    }

    public Optional<AnalysisSummary> find(UUID analysisId) {
        return jdbcTemplate.query(
                """
                SELECT id, route_id, status, frame_count, model_version
                FROM route_analysis
                WHERE id = ?
                """,
                (resultSet, rowNumber) -> new AnalysisSummary(
                        UUID.fromString(resultSet.getString("id")),
                        UUID.fromString(resultSet.getString("route_id")),
                        resultSet.getString("status"),
                        resultSet.getInt("frame_count"),
                        resultSet.getString("model_version")),
                analysisId).stream().findFirst();
    }

    public void updateStatus(UUID analysisId, AnalysisStatus status) {
        jdbcTemplate.update(
                "UPDATE route_analysis SET status = ? WHERE id = ?",
                status.value(),
                analysisId);
    }

    /** Sampled images with no stored analysis for the current model version. */
    public List<String> unscoredImageIds(UUID analysisId, String modelVersion) {
        return jdbcTemplate.queryForList(
                """
                SELECT frame.mapillary_id
                FROM route_analysis_frame frame
                WHERE frame.analysis_id = ?
                  AND NOT EXISTS (
                    SELECT 1 FROM image_analysis analysis
                    WHERE analysis.mapillary_id = frame.mapillary_id
                      AND analysis.model_version = ?
                  )
                ORDER BY frame.seq
                """,
                String.class,
                analysisId,
                modelVersion);
    }

    public record AnalysisSummary(
            UUID id,
            UUID routeId,
            String status,
            int frameCount,
            String modelVersion) {
    }
}
