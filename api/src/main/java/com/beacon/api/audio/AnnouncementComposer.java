package com.beacon.api.audio;

import com.beacon.api.hazards.Hazard;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes the spoken lines for a route.
 *
 * <p>Turn instructions come from the router. What makes this the app's own
 * voice is the exposure commentary interleaved between them, generated from
 * the route's measured exposure and the hazards the person actually weighted.
 *
 * <p>Copy rules, deliberately strict. Announcements describe the street, never
 * the listener's body: "this stretch has higher traffic exhaust" is a
 * statement about a place, while "your asthma may worsen here" is a clinical
 * prediction this app is in no position to make. Nothing here tells anyone to
 * change medication or abandon a trip, and no absolute concentrations are
 * quoted, because the underlying surfaces support relative comparison only.
 */
public final class AnnouncementComposer {

    /** Above this percentile a hazard is worth mentioning at all. */
    static final double MENTION_THRESHOLD = 60.0;
    private static final int MAX_HAZARD_ANNOUNCEMENTS = 3;

    private AnnouncementComposer() {
    }

    /**
     * Composes the announcement list for a route.
     *
     * @param exposure   length-weighted exposure per hazard key, 0-100
     * @param weights    the profile's hazard weights, so advice matches concern
     * @param distanceM  route length, used to place the closing line
     */
    public static List<Announcement> compose(
            Map<String, Double> exposure,
            Map<Hazard, Double> weights,
            double distanceM) {
        List<Announcement> announcements = new ArrayList<>();
        announcements.add(new Announcement(0.0, "Starting your route. " + openingLine(exposure, weights)));

        List<Map.Entry<Hazard, Double>> concerning = weights.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue() > 0.0)
                .filter(entry -> exposureOf(exposure, entry.getKey()) >= MENTION_THRESHOLD)
                .sorted(Comparator.comparingDouble(
                        (Map.Entry<Hazard, Double> entry) ->
                                entry.getValue() * exposureOf(exposure, entry.getKey()))
                        .reversed())
                .limit(MAX_HAZARD_ANNOUNCEMENTS)
                .toList();

        // Spread the commentary through the walk rather than front-loading it.
        double spacing = concerning.isEmpty() ? 0 : distanceM / (concerning.size() + 1);
        for (int index = 0; index < concerning.size(); index++) {
            Hazard hazard = concerning.get(index).getKey();
            double offset = Math.round(spacing * (index + 1));
            announcements.add(new Announcement(offset, hazardLine(hazard)));
        }

        announcements.add(new Announcement(Math.max(distanceM - 30, 0), "You have arrived."));
        return List.copyOf(announcements);
    }

    private static double exposureOf(Map<String, Double> exposure, Hazard hazard) {
        Double value = exposure.get(hazard.key());
        return value == null ? 0.0 : value;
    }

    private static String openingLine(Map<String, Double> exposure, Map<Hazard, Double> weights) {
        boolean anythingElevated = weights.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue() > 0.0)
                .anyMatch(entry -> exposureOf(exposure, entry.getKey()) >= MENTION_THRESHOLD);
        return anythingElevated
                ? "This is the cleaner of the options for the conditions you chose."
                : "Conditions along this route look unremarkable today.";
    }

    /**
     * One line per hazard, describing the street and roughly what to expect.
     *
     * <p>Kept plain and non-clinical on purpose; see the class comment.
     */
    static String hazardLine(Hazard hazard) {
        return switch (hazard) {
            case PM25 -> "This stretch carries more particulate pollution than the rest of your route.";
            case NO2 -> "Traffic exhaust is heavier along here.";
            case OZONE -> "Ozone tends to run higher on open stretches like this one in the afternoon.";
            case POLLEN_TREE -> "Tree pollen is elevated here. You may want to cover your face.";
            case POLLEN_GRASS -> "Grass pollen is elevated along this section.";
            case POLLEN_WEED -> "Weed pollen is elevated along this section.";
            case TRAFFIC_PROX -> "You are close to a busy road for the next few minutes.";
            case CONSTRUCTION -> "There is active construction nearby.";
            case INDUSTRIAL_PROX -> "This passes near industrial sites.";
            case GRADE -> "The next part climbs a little more steeply.";
            case HEAT -> "This section has little shade in the heat.";
            case COLD_AIR -> "This stretch is exposed to cold air.";
            case HUMIDITY -> "The air is heavier and more humid along here.";
            case CROWD_DENSITY -> "This stretch is usually busy with people and traffic.";
            case SHADE_DEFICIT -> "There is little tree cover along this part.";
        };
    }

    /** A single spoken line and where along the route it belongs. */
    public record Announcement(double distanceOffsetM, String text) {

        public Announcement {
            text = text == null ? "" : text.strip();
            distanceOffsetM = Math.max(distanceOffsetM, 0.0);
        }

        public String describeOffset() {
            return distanceOffsetM >= 1000
                    ? String.format(Locale.ROOT, "%.1f km", distanceOffsetM / 1000)
                    : String.format(Locale.ROOT, "%.0f m", distanceOffsetM);
        }
    }
}
