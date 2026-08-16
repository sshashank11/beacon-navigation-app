package com.beacon.api.tiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class HazardTileRepositoryTest {

    @Test
    void fetchesRequestedTileWithWhitelistedScoreColumn() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        byte[] expected = {1, 2, 3};
        when(jdbc.queryForObject(anyString(), eq(byte[].class), eq(12), eq(1206), eq(1540)))
                .thenReturn(expected);

        byte[] result = new HazardTileRepository(jdbc)
                .tile(HazardTile.PM25, 12, 1206, 1540);

        assertThat(result).isSameAs(expected);
        verify(jdbc).queryForObject(
                org.mockito.ArgumentMatchers.contains("score.pm25_prior"),
                eq(byte[].class),
                eq(12),
                eq(1206),
                eq(1540));
    }

    @Test
    void rejectsCoordinatesOutsideTheRequestedZoom() {
        HazardTileRepository repository = new HazardTileRepository(mock(JdbcTemplate.class));

        assertThatThrownBy(() -> repository.tile(HazardTile.NO2, 4, 16, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Tile coordinates are outside zoom 4");
        assertThatThrownBy(() -> repository.tile(HazardTile.NO2, 23, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Tile zoom must be between 0 and 22");
    }

    @Test
    void parsesOnlySupportedHazardSlugs() {
        assertThat(HazardTile.fromSlug("industrial")).isEqualTo(HazardTile.INDUSTRIAL);
        assertThatThrownBy(() -> HazardTile.fromSlug("made_up"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
