package com.beacon.api.routing.score;

import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

@Repository
public class SegmentScoreRepository {

    private static final int EXPECTED_NYC_WAY_COUNT = 400_000;
    private static final String SCORE_QUERY = """
            SELECT
              segment.osm_way_id,
              %s,
              %s,
              %s,
              %s,
              %s,
              %s,
              %s,
              %s,
              %s
            FROM segment
            LEFT JOIN segment_static_score AS score ON score.segment_id = segment.id
            LEFT JOIN segment_industrial_sample AS industrial
              ON industrial.segment_id = segment.id
            GROUP BY segment.osm_way_id
            """.formatted(
            weightedAverage("pm25_prior"),
            weightedAverage("no2_prior"),
            weightedAverage("ozone_prior"),
            weightedAverage("traffic_prox"),
            weightedAverage("industrial_prox"),
            weightedAverage("shade_benefit"),
            weightedAverage("pollen_source"),
            weightedGrade(),
            industrialWithin200m());

    private final JdbcTemplate jdbcTemplate;

    public SegmentScoreRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public SegmentScoreIndex loadIndex() {
        SegmentScoreIndex index = new SegmentScoreIndex(EXPECTED_NYC_WAY_COUNT);
        jdbcTemplate.query(
                SCORE_QUERY,
                (RowCallbackHandler) resultSet -> addScore(index, resultSet));
        return index;
    }

    private static void addScore(SegmentScoreIndex index, ResultSet resultSet) throws SQLException {
        index.put(
                resultSet.getLong(1),
                resultSet.getDouble(2),
                resultSet.getDouble(3),
                resultSet.getDouble(4),
                resultSet.getDouble(5),
                resultSet.getDouble(6),
                resultSet.getDouble(7),
                resultSet.getDouble(8),
                resultSet.getDouble(9),
                resultSet.getDouble(10));
    }

    private static String weightedAverage(String column) {
        return """
                coalesce(
                  sum(score.%1$s * segment.length_m)
                    / nullif(sum(segment.length_m) filter (where score.%1$s is not null), 0),
                  0
                )
                """.formatted(column).strip();
    }

    private static String weightedGrade() {
        return """
                coalesce(
                  sum(abs(segment.grade_pct) * segment.length_m)
                    / nullif(sum(segment.length_m) filter (where segment.grade_pct is not null), 0),
                  0
                )
                """.strip();
    }

    private static String industrialWithin200m() {
        return """
                coalesce(
                  max(case when industrial.nearest_facility_m <= 200 then 100 else 0 end),
                  0
                )
                """.strip();
    }
}
