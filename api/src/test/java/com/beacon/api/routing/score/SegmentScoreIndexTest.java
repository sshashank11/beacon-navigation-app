package com.beacon.api.routing.score;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SegmentScoreIndexTest {

    @Test
    void packsAndReadsAllScoresAcrossThreePacks() {
        SegmentScoreIndex index = new SegmentScoreIndex(1);

        index.put(42L, 1.2, 20.5, 39.6, 58.8, 77.7, 99.9, 110.0, 6.2, 100.0, 12.4, 88.6);

        assertThat(index.size()).isEqualTo(1);
        assertThat(index.get(42L, StaticScore.PM25)).isEqualTo(1);
        assertThat(index.get(42L, StaticScore.NO2)).isEqualTo(21);
        assertThat(index.get(42L, StaticScore.OZONE)).isEqualTo(40);
        assertThat(index.get(42L, StaticScore.TRAFFIC)).isEqualTo(59);
        assertThat(index.get(42L, StaticScore.INDUSTRIAL)).isEqualTo(78);
        assertThat(index.get(42L, StaticScore.SHADE)).isEqualTo(100);
        assertThat(index.get(42L, StaticScore.POLLEN)).isEqualTo(100);
        assertThat(index.get(42L, StaticScore.GRADE)).isEqualTo(6);
        assertThat(index.get(42L, StaticScore.INDUSTRIAL_WITHIN_200M)).isEqualTo(100);
        assertThat(index.get(42L, StaticScore.SKY_VIEW)).isEqualTo(12);
        assertThat(index.get(42L, StaticScore.CROWD)).isEqualTo(89);
        assertThat(index.get(99L, StaticScore.PM25)).isZero();
    }

    @Test
    void theNoDataSentinelSurvivesPacking() {
        SegmentScoreIndex index = new SegmentScoreIndex(1);

        index.put(7L, 10, 10, 10, 10, 10, 10, 10, 5, 0,
                StaticScore.NO_DATA, StaticScore.NO_DATA);

        // A segment with no imagery must not read as the lowest percentile.
        assertThat(index.get(7L, StaticScore.SKY_VIEW)).isEqualTo(StaticScore.NO_DATA);
        assertThat(index.get(7L, StaticScore.CROWD)).isEqualTo(StaticScore.NO_DATA);
        assertThat(StaticScore.NO_DATA).isGreaterThan(100);
    }

    @Test
    void onlyImageryScoresAreOptional() {
        assertThat(StaticScore.SKY_VIEW.optional()).isTrue();
        assertThat(StaticScore.CROWD.optional()).isTrue();
        assertThat(StaticScore.PM25.optional()).isFalse();
        assertThat(StaticScore.GRADE.optional()).isFalse();
    }

    @Test
    void nonFiniteAndNegativeScoresBecomeZero() {
        assertThat(SegmentScoreIndex.quantize(Double.NaN)).isZero();
        assertThat(SegmentScoreIndex.quantize(Double.POSITIVE_INFINITY)).isZero();
        assertThat(SegmentScoreIndex.quantize(-20)).isZero();
    }
}
