package com.beacon.api.audio;

import com.beacon.api.users.CallerResolver;
import java.security.Principal;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "beacon.routing", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RouteAudioController {

    private final RouteAudioService audio;
    private final SpeechService speech;
    private final CallerResolver callers;

    public RouteAudioController(
            RouteAudioService audio,
            SpeechService speech,
            CallerResolver callers) {
        this.audio = audio;
        this.speech = speech;
        this.callers = callers;
    }

    /** Manifest of every line for a route, with a URL per preloadable clip. */
    @GetMapping("/routes/{routeId}/audio")
    public RouteAudioService.RouteAudioManifest manifest(
            @PathVariable UUID routeId,
            Principal principal) {
        return audio.manifest(routeId, callers.require(principal));
    }

    /**
     * Serves one synthesised clip.
     *
     * <p>Addressed by content hash rather than by route, because the same line
     * is the same audio on every route that says it.
     */
    @GetMapping("/audio/{key}")
    public ResponseEntity<byte[]> clip(@PathVariable String key) {
        if (!key.matches("[0-9a-f]{64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed clip key");
        }
        return speech.find(key)
                .map(clip -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(clip.contentType()))
                        .header("Cache-Control", "public, max-age=31536000, immutable")
                        .body(clip.audio()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No audio for that key"));
    }
}
