package com.beacon.api.routing.score;

import com.graphhopper.routing.ev.DefaultImportRegistry;
import com.graphhopper.routing.ev.EncodedValueLookup;
import com.graphhopper.routing.ev.ImportRegistry;
import com.graphhopper.routing.ev.ImportUnit;
import com.graphhopper.routing.ev.IntEncodedValue;
import com.graphhopper.routing.ev.IntEncodedValueImpl;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

public final class ClearwayImportRegistry implements ImportRegistry {

    public static final String SCORE_IMPORT_UNIT = "clw_scores";
    private static final int SCORE_BITS = 7;

    private final DefaultImportRegistry delegate = new DefaultImportRegistry();
    private final SegmentScoreIndex scores;

    public ClearwayImportRegistry(SegmentScoreIndex scores) {
        this.scores = scores;
    }

    @Override
    public ImportUnit createImportUnit(String name) {
        StaticScore score = StaticScore.fromEncodedValueName(name).orElse(null);
        if (score != null) {
            return ImportUnit.create(
                    name,
                    properties -> new IntEncodedValueImpl(name, SCORE_BITS, false),
                    null);
        }
        if (SCORE_IMPORT_UNIT.equals(name)) {
            return ImportUnit.create(
                    name,
                    null,
                    (lookup, properties) -> new ClearwayScoreParser(
                            scores,
                            encodedValues(lookup)),
                    Arrays.stream(StaticScore.values())
                            .map(StaticScore::encodedValueName)
                            .toArray(String[]::new));
        }
        return delegate.createImportUnit(name);
    }

    private static Map<StaticScore, IntEncodedValue> encodedValues(
            EncodedValueLookup lookup) {
        Map<StaticScore, IntEncodedValue> encodedValues = new EnumMap<>(StaticScore.class);
        for (StaticScore score : StaticScore.values()) {
            encodedValues.put(
                    score,
                    lookup.getIntEncodedValue(score.encodedValueName()));
        }
        return encodedValues;
    }
}
