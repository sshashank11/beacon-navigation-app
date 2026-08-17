package com.beacon.api.routing.score;

import com.carrotsearch.hppc.LongIntHashMap;

public final class SegmentScoreIndex {

    static final int BITS_PER_SCORE = 7;
    static final int SCORE_MASK = (1 << BITS_PER_SCORE) - 1;
    private static final int FIRST_PACK_SIZE = 4;
    private static final int SECOND_PACK_SIZE = 4;
    /** Four 7-bit scores are all that fit in one 32-bit pack. */
    private static final int THIRD_PACK_SIZE = 4;
    private static final int MAX_SCORES = FIRST_PACK_SIZE + SECOND_PACK_SIZE + THIRD_PACK_SIZE;

    static {
        if (StaticScore.values().length > MAX_SCORES) {
            throw new IllegalStateException(
                    "StaticScore has outgrown the three 7-bit packs (" + MAX_SCORES
                            + " max); add a fourth pack before adding another score");
        }
    }

    private final LongIntHashMap firstPack;
    private final LongIntHashMap secondPack;
    private final LongIntHashMap thirdPack;

    public SegmentScoreIndex(int expectedWayCount) {
        firstPack = new LongIntHashMap(expectedWayCount);
        secondPack = new LongIntHashMap(expectedWayCount);
        thirdPack = new LongIntHashMap(expectedWayCount);
    }

    public static SegmentScoreIndex empty() {
        return new SegmentScoreIndex(0);
    }

    public void put(long osmWayId, double... scores) {
        if (scores.length != StaticScore.values().length) {
            throw new IllegalArgumentException(
                    "Expected " + StaticScore.values().length + " scores, got " + scores.length);
        }
        int first = 0;
        int second = 0;
        int third = 0;
        for (int index = 0; index < scores.length; index++) {
            int quantized = quantize(scores[index], StaticScore.values()[index]);
            if (index < FIRST_PACK_SIZE) {
                first |= quantized << (index * BITS_PER_SCORE);
            } else if (index < FIRST_PACK_SIZE + SECOND_PACK_SIZE) {
                second |= quantized << ((index - FIRST_PACK_SIZE) * BITS_PER_SCORE);
            } else {
                third |= quantized << ((index - FIRST_PACK_SIZE - SECOND_PACK_SIZE) * BITS_PER_SCORE);
            }
        }
        firstPack.put(osmWayId, first);
        secondPack.put(osmWayId, second);
        thirdPack.put(osmWayId, third);
    }

    public int get(long osmWayId, StaticScore score) {
        int ordinal = score.ordinal();
        if (ordinal < FIRST_PACK_SIZE) {
            return unpack(firstPack.getOrDefault(osmWayId, 0), ordinal);
        }
        if (ordinal < FIRST_PACK_SIZE + SECOND_PACK_SIZE) {
            return unpack(secondPack.getOrDefault(osmWayId, 0), ordinal - FIRST_PACK_SIZE);
        }
        return unpack(
                thirdPack.getOrDefault(osmWayId, 0),
                ordinal - FIRST_PACK_SIZE - SECOND_PACK_SIZE);
    }

    public int size() {
        return firstPack.size();
    }

    /** Packs a mandatory score, clamped to the 0-100 percentile range. */
    static int quantize(double score) {
        return quantize(score, null);
    }

    /**
     * Packs a score into 7 bits.
     *
     * <p>Only optional scores may exceed 100, and only to carry {@link
     * StaticScore#NO_DATA}. Mandatory scores keep the 0-100 clamp as a guard
     * against a bad value reaching the graph.
     */
    static int quantize(double score, StaticScore field) {
        if (!Double.isFinite(score)) {
            return 0;
        }
        double ceiling = field != null && field.optional()
                ? StaticScore.NO_DATA
                : 100.0;
        return (int) Math.round(Math.clamp(score, 0.0, ceiling));
    }

    private static int unpack(int packed, int index) {
        return (packed >>> (index * BITS_PER_SCORE)) & SCORE_MASK;
    }
}
