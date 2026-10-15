package com.tinyme.api.places;

import com.tinyme.domain.places.model.PlaceSnapshot;
import com.tinyme.domain.places.service.PlaceService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlaceControllerTests {
    private static final Instant CREATED = Instant.parse("2026-10-15T03:00:00Z");

    @Test
    void listMapsAppFiltersAndReturnsCoordinatesAndDistance() {
        PlaceService service = mock(PlaceService.class);
        UUID id = UUID.randomUUID();
        when(service.listForApp("coffee", false, 35.0, 139.0)).thenReturn(List.of(
                new PlaceSnapshot(id, "Blue Bottle", "Shibuya, Tokyo", "Tokyo", "pour-over", "cafe", null,
                        false, CREATED, 35.1, 139.1, null, 812.4)));

        var response = new PlaceController(service, Clock.systemUTC()).list("coffee", false, 35.0, 139.0).getFirst();

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.area()).isEqualTo("Tokyo");
        assertThat(response.lat()).isEqualTo(35.1);
        assertThat(response.distanceM()).isEqualTo(812L);
        verify(service).listForApp("coffee", false, 35.0, 139.0);
    }

    @Test
    void watchReturnsNearestUnvisitedCandidates() {
        PlaceService service = mock(PlaceService.class);
        UUID id = UUID.randomUUID();
        when(service.watch(35.0, 139.0)).thenReturn(List.of(snapshot(id)));

        var result = new PlaceController(service, Clock.systemUTC()).watch(35.0, 139.0);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().id()).isEqualTo(id);
        verify(service).watch(35.0, 139.0);
    }

    @Test
    void patchForwardsOnlySuppliedFieldsAndReturnsNotFoundForDeletedPlaces() throws Exception {
        PlaceService service = mock(PlaceService.class);
        UUID id = UUID.randomUUID();
        when(service.update(org.mockito.ArgumentMatchers.eq(id), org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> new PlaceController(service, Clock.systemUTC()).update(id,
                JsonMapper.builder().build().readTree("{\"visited\":true}")))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);
        verify(service).update(org.mockito.ArgumentMatchers.eq(id), org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void removeIs204ForLiveAnd404AfterItIsGone() {
        PlaceService service = mock(PlaceService.class);
        UUID id = UUID.randomUUID();
        when(service.remove(org.mockito.ArgumentMatchers.eq(id), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(snapshot(id))).thenReturn(Optional.empty());
        PlaceController controller = new PlaceController(service, Clock.systemUTC());

        assertThat(controller.delete(id).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThatThrownBy(() -> controller.delete(id))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);
    }

    private static PlaceSnapshot snapshot(UUID id) {
        return new PlaceSnapshot(id, "Cafe", "Shibuya, Tokyo", "Tokyo", null, "cafe", null,
                false, CREATED, 35.0, 139.0, null, null);
    }
}
