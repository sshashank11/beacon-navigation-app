package com.beacon.api.tiles;

import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
@RequestMapping("/api/v1/tiles/hazard")
public class HazardTileController {

    static final MediaType MVT_MEDIA_TYPE = MediaType.parseMediaType(
            "application/vnd.mapbox-vector-tile");

    private final HazardTileRepository tiles;

    public HazardTileController(HazardTileRepository tiles) {
        this.tiles = tiles;
    }

    @GetMapping(value = "/{hazard}/{zoom}/{x}/{y}.mvt", produces = "application/vnd.mapbox-vector-tile")
    public ResponseEntity<byte[]> tile(
            @PathVariable String hazard,
            @PathVariable int zoom,
            @PathVariable int x,
            @PathVariable int y) {
        try {
            byte[] body = tiles.tile(HazardTile.fromSlug(hazard), zoom, x, y);
            return ResponseEntity.ok()
                    .contentType(MVT_MEDIA_TYPE)
                    .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                    .body(body);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(BAD_REQUEST, exception.getMessage(), exception);
        }
    }
}
