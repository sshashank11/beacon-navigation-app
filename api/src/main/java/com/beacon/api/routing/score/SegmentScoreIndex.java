package com.beacon.api.routing.score;

import com.carrotsearch.hppc.LongIntHashMap;

public final class SegmentScoreIndex {

    static final int BITS_PER_SCORE = 7;
    static final int SCORE_MASK = (1 << BITS_PER_SCORE) - 1;
    private static final int FIRST_PACK_SIZE = 4;

    private final LongIntHashMap firstPack;
    private final LongIntHashMap secondPack;

    public SegmentScoreIndex(int expectedWayCount) {
        firstPack = new LongIntHashMap(expectedWayCount);
        secondPack = new LongIntHashMap(expectedWayCount);
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
        for (int index = 0; index < scores.length; index++) {
            int quantized = quantize(scores[index]);
            if (index < FIRST_PACK_SIZE) {
                first |= quantized << (index * BITS_PER_SCORE);
            } else {
                second |= quantized << ((index - FIRST_PACK_SIZE) * BITS_PER_SCORE);
            }
        }
        firstPack.put(osmWayId, first);
        secondPack.put(osmWayId, second);
    }

    public int get(long osmWayId, StaticScore score) {
        int ordinal = score.ordinal();
        if (ordinal < FIRST_PACK_SIZE) {
            return unpack(firstPack.getOrDefault(osmWayId, 0), ordinal);
        }
        return unpack(secondPack.getOrDefault(osmWayId, 0), ordinal - FIRST_PACK_SIZE);
    }

    public int size() {
        return firstPack.size();
    }

    static int quantize(double score) {
        if (!Double.isFinite(score)) {
            return 0;
        }
        return (int) Math.round(Math.clamp(score, 0.0, 100.0));
    }

    private static int unpack(int packed, int index) {
        return (packed >>> (index * BITS_PER_SCORE)) & SCORE_MASK;
    }
}
