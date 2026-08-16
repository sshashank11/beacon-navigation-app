package com.beacon.api.profiles;

import static org.assertj.core.api.Assertions.assertThat;

import com.beacon.api.hazards.Hazard;
import org.junit.jupiter.api.Test;

class ProfilePresetsTest {

    @Test
    void definesAllSixConditionPresets() {
        assertThat(ProfilePresets.all()).containsOnlyKeys(ProfilePreset.values());
        assertThat(ProfilePresets.get(ProfilePreset.ASTHMA).weights())
                .containsEntry(Hazard.PM25, 3.0)
                .containsEntry(Hazard.TRAFFIC_PROX, 2.0);
        assertThat(ProfilePresets.get(ProfilePreset.ALLERGIES).weights())
                .containsEntry(Hazard.POLLEN_TREE, 3.0);
        assertThat(ProfilePresets.get(ProfilePreset.COPD).hardAvoids())
                .containsExactly(HardAvoid.GRADE_ABOVE_SIX_PERCENT);
        assertThat(ProfilePresets.get(ProfilePreset.CHEMICAL_SENSITIVITY).hardAvoids())
                .contains(HardAvoid.ACTIVE_CONSTRUCTION_FRONTAGE, HardAvoid.INDUSTRIAL_WITHIN_200M);
        assertThat(ProfilePresets.get(ProfilePreset.CARDIAC).maxGradePct()).isEqualTo(5.0);
        assertThat(ProfilePresets.get(ProfilePreset.NONE).weights()).isEmpty();
    }
}
