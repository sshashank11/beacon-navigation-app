package com.beacon.api.audio;

import com.beacon.api.hazards.Hazard;
import com.beacon.api.routing.RouteHistoryRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Builds the audio manifest for a saved route.
 *
 * <p>Everything is synthesised up front and addressed by cache key, so the
 * client can preload every clip when the route is accepted and play by
 * geolocation trigger. Nothing here happens mid-turn.
 */
@Service
public class RouteAudioService {

    private final RouteHistoryRepository routes;
    private final SpeechService speech;

    public RouteAudioService(RouteHistoryRepository routes, SpeechService speech) {
        this.routes = routes;
        this.speech = speech;
    }

    public RouteAudioManifest manifest(UUID routeId, UUID userId) {
        if (!routes.isOwnedBy(routeId, userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Route was not found");
        }

        RouteHistoryRepository.RouteAudioSource source = routes.audioSource(routeId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Route was not found"));

        Map<Hazard, Double> weights = inferWeights(source.exposureBreakdown());
        List<AnnouncementComposer.Announcement> lines = AnnouncementComposer.compose(
                source.exposureBreakdown(), weights, source.distanceM());

        List<RouteAudioClip> clips = new ArrayList<>();
        for (AnnouncementComposer.Announcement line : lines) {
            Optional<SpeechService.CachedClip> spoken = speech.speak(line.text());
            clips.add(new RouteAudioClip(
                    line.distanceOffsetM(),
                    line.text(),
                    spoken.map(clip -> "/api/v1/audio/" + clip.key()).orElse(null),
                    spoken.isPresent()));
        }

        return new RouteAudioManifest(
                routeId,
                source.distanceM(),
                speech.isAvailable(),
                clips);
    }

    /**
     * Treats every hazard the route measured as one worth commenting on.
     *
     * <p>Profiles are not persisted, so the weights that produced this route
     * are gone by now. The exposure breakdown is what remains, and it only
     * contains hazards that were actually scored, which is a reasonable stand-in.
     */
    private static Map<Hazard, Double> inferWeights(Map<String, Double> exposure) {
        Map<Hazard, Double> weights = new java.util.EnumMap<>(Hazard.class);
        for (Hazard hazard : Hazard.values()) {
            if (exposure.containsKey(hazard.key())) {
                weights.put(hazard, 1.0);
            }
        }
        return weights;
    }

    public record RouteAudioClip(
            double distanceOffsetM,
            String text,
            String audioUrl,
            boolean hasAudio) {
    }

    public record RouteAudioManifest(
            UUID routeId,
            double distanceM,
            boolean speechAvailable,
            List<RouteAudioClip> clips) {
    }
}
