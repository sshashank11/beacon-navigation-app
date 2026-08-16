package com.beacon.api.routing.score;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.EdgeIntAccess;
import com.graphhopper.routing.ev.IntEncodedValue;
import com.graphhopper.routing.util.parsers.TagParser;
import com.graphhopper.storage.IntsRef;
import java.util.EnumMap;
import java.util.Map;

public final class ClearwayScoreParser implements TagParser {

    private final SegmentScoreIndex scores;
    private final Map<StaticScore, IntEncodedValue> encodedValues;

    public ClearwayScoreParser(
            SegmentScoreIndex scores,
            Map<StaticScore, IntEncodedValue> encodedValues) {
        this.scores = scores;
        this.encodedValues = new EnumMap<>(encodedValues);
        if (this.encodedValues.size() != StaticScore.values().length) {
            throw new IllegalArgumentException("Every static score requires an encoded value");
        }
    }

    @Override
    public void handleWayTags(
            int edgeId,
            EdgeIntAccess edgeIntAccess,
            ReaderWay way,
            IntsRef relationFlags) {
        for (StaticScore score : StaticScore.values()) {
            encodedValues.get(score).setInt(
                    false,
                    edgeId,
                    edgeIntAccess,
                    scores.get(way.getId(), score));
        }
    }
}
