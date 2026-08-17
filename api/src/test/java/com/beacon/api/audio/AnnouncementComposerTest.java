package com.beacon.api.audio;

import static org.assertj.core.api.Assertions.assertThat;

import com.beacon.api.hazards.Hazard;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnnouncementComposerTest {

    /**
     * Words that would turn a description of a street into a clinical claim
     * about the listener, or into advice this app has no standing to give.
     */
    private static final List<String> FORBIDDEN = List.of(
            "asthma", "copd", "diagnos", "symptom", "medication", "inhaler",
            "your condition", "attack", "you should not", "do not walk",
            "dangerous", "unsafe", "toxic", "risk of", "may worsen",
            "consult", "prescri", "treat");

    @Test
    void noAnnouncementMakesAClinicalClaim() {
        for (Hazard hazard : Hazard.values()) {
            String line = AnnouncementComposer.hazardLine(hazard).toLowerCase(Locale.ROOT);
            assertThat(FORBIDDEN)
                    .as("copy for %s must describe the street, not the listener: %s", hazard, line)
                    .noneSatisfy(banned -> assertThat(line).contains(banned));
        }
    }

    @Test
    void everyHazardHasCopyThatReadsAsASentence() {
        for (Hazard hazard : Hazard.values()) {
            String line = AnnouncementComposer.hazardLine(hazard);
            assertThat(line).isNotBlank();
            assertThat(line).endsWith(".");
            assertThat(line.length()).isBetween(20, 160);
        }
    }

    @Test
    void quotesNoAbsoluteConcentrations() {
        // NYCCAS surfaces support relative comparison, not absolute exposure,
        // so no announcement may imply a measured concentration.
        for (Hazard hazard : Hazard.values()) {
            assertThat(AnnouncementComposer.hazardLine(hazard))
                    .doesNotContainIgnoringCase("µg")
                    .doesNotContainIgnoringCase("ug/m")
                    .doesNotContainIgnoringCase("ppb")
                    .doesNotContainIgnoringCase("aqi")
                    .doesNotMatch(".*[0-9]+ ?(percent|%).*");
        }
    }

    @Test
    void onlyElevatedAndWeightedHazardsAreMentioned() {
        Map<String, Double> exposure = Map.of("pm25", 90.0, "ozone", 10.0);
        Map<Hazard, Double> weights = Map.of(Hazard.PM25, 3.0, Hazard.OZONE, 3.0);

        List<AnnouncementComposer.Announcement> lines =
                AnnouncementComposer.compose(exposure, weights, 1000);

        String all = lines.stream().map(AnnouncementComposer.Announcement::text)
                .reduce("", (left, right) -> left + " " + right);
        assertThat(all).contains("particulate");
        assertThat(all).doesNotContain("Ozone");
    }

    @Test
    void aHazardTheProfileIgnoresIsNotMentioned() {
        Map<String, Double> exposure = Map.of("pollen_tree", 95.0);
        Map<Hazard, Double> weights = Map.of(Hazard.POLLEN_TREE, 0.0);

        List<AnnouncementComposer.Announcement> lines =
                AnnouncementComposer.compose(exposure, weights, 800);

        assertThat(lines).noneMatch(line -> line.text().contains("pollen"));
    }

    @Test
    void announcementsAreOrderedAlongTheRouteAndBookended() {
        Map<String, Double> exposure = Map.of("pm25", 90.0, "traffic_prox", 80.0);
        Map<Hazard, Double> weights = Map.of(Hazard.PM25, 3.0, Hazard.TRAFFIC_PROX, 2.0);

        List<AnnouncementComposer.Announcement> lines =
                AnnouncementComposer.compose(exposure, weights, 1200);

        assertThat(lines.get(0).distanceOffsetM()).isZero();
        assertThat(lines.get(lines.size() - 1).text()).isEqualTo("You have arrived.");
        assertThat(lines).isSortedAccordingTo(
                java.util.Comparator.comparingDouble(
                        AnnouncementComposer.Announcement::distanceOffsetM));
    }

    @Test
    void anUnremarkableRouteStillGetsAnOpeningAndClosing() {
        List<AnnouncementComposer.Announcement> lines =
                AnnouncementComposer.compose(Map.of("pm25", 10.0), Map.of(Hazard.PM25, 3.0), 500);

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).text()).contains("unremarkable");
    }

    @Test
    void atMostThreeHazardsAreMentionedSoTheWalkIsNotNarrated() {
        Map<String, Double> exposure = Map.of(
                "pm25", 95.0, "no2", 94.0, "ozone", 93.0,
                "traffic_prox", 92.0, "industrial_prox", 91.0);
        Map<Hazard, Double> weights = Map.of(
                Hazard.PM25, 3.0, Hazard.NO2, 3.0, Hazard.OZONE, 3.0,
                Hazard.TRAFFIC_PROX, 3.0, Hazard.INDUSTRIAL_PROX, 3.0);

        List<AnnouncementComposer.Announcement> lines =
                AnnouncementComposer.compose(exposure, weights, 2000);

        assertThat(lines).hasSize(5); // opening + 3 hazards + arrival
    }
}
