package com.beacon.api.tiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class HazardTileControllerTest {

    @Test
    void returnsCacheableMapboxVectorTile() {
        HazardTileRepository repository = mock(HazardTileRepository.class);
        byte[] tile = {4, 5, 6};
        when(repository.tile(HazardTile.PM25, 12, 1206, 1540)).thenReturn(tile);

        var response = new HazardTileController(repository)
                .tile("pm25", 12, 1206, 1540);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(HazardTileController.MVT_MEDIA_TYPE);
        assertThat(response.getHeaders().getCacheControl()).contains("public", "max-age=3600");
        assertThat(response.getBody()).isSameAs(tile);
    }

    @Test
    void turnsInvalidHazardIntoBadRequest() {
        HazardTileController controller = new HazardTileController(mock(HazardTileRepository.class));

        assertThatThrownBy(() -> controller.tile("noise", 12, 1206, 1540))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
