package com.beacon.api.routing.score;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.ArrayEdgeIntAccess;
import com.graphhopper.routing.ev.EncodedValue;
import com.graphhopper.routing.ev.IntEncodedValue;
import com.graphhopper.routing.ev.IntEncodedValueImpl;
import com.graphhopper.storage.IntsRef;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClearwayScoreParserTest {

    @Test
    void writesEveryPackedScoreToTheEdge() {
        SegmentScoreIndex index = new SegmentScoreIndex(1);
        index.put(17L, 80, 70, 60, 50, 40, 30, 20, 10, 100);
        Map<StaticScore, IntEncodedValue> encodedValues = encodedValues();
        EncodedValue.InitializerConfig initializer = new EncodedValue.InitializerConfig();
        encodedValues.values().forEach(value -> value.init(initializer));
        ArrayEdgeIntAccess edgeAccess = new ArrayEdgeIntAccess(initializer.getRequiredInts());
        ClearwayScoreParser parser = new ClearwayScoreParser(index, encodedValues);

        parser.handleWayTags(0, edgeAccess, new ReaderWay(17L), IntsRef.EMPTY);

        for (StaticScore score : StaticScore.values()) {
            assertThat(encodedValues.get(score).getInt(false, 0, edgeAccess))
                    .isEqualTo(index.get(17L, score));
        }
    }

    private static Map<StaticScore, IntEncodedValue> encodedValues() {
        Map<StaticScore, IntEncodedValue> values = new EnumMap<>(StaticScore.class);
        for (StaticScore score : StaticScore.values()) {
            values.put(score, new IntEncodedValueImpl(
                    score.encodedValueName(),
                    SegmentScoreIndex.BITS_PER_SCORE,
                    false));
        }
        return values;
    }
}
