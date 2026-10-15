package com.tinyme.tools.handlers;

import com.tinyme.agent.model.DeviceToolResult;
import com.tinyme.agent.model.TurnEvent;
import com.tinyme.agent.model.TurnLocation;
import com.tinyme.agent.service.DeviceToolGateway;
import com.tinyme.agent.service.TurnListener;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlacesSearchToolTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void sendsDefaultHereAndReturnsOpaqueCandidateIdsWithoutCoordinates() throws Exception {
        DeviceToolGateway gateway = mock(DeviceToolGateway.class);
        UUID actionId = UUID.randomUUID();
        when(gateway.callWithActionId(any(), eq("places_search"), any(), any()))
                .thenReturn(new DeviceToolGateway.CompletedDeviceAction(actionId,
                        DeviceToolResult.success(JSON.readTree("""
                                [{"name":"Blue Bottle","address":"Shibuya, Tokyo","lat":35.6,"lng":139.7,
                                  "category":"cafe","provider_place_id":"map-1","distance_m":250}]
                                """))));
        PlacesSearchTool tool = new PlacesSearchTool(gateway);
        ToolResult result = tool.handle(JSON.readTree("{\"query\":\"Blue Bottle\"}"), context(true));

        assertThat(result).isInstanceOf(ToolResult.Ok.class);
        ToolResult.Ok ok = (ToolResult.Ok) result;
        assertThat(ok.data().toString()).contains(actionId + ":0", "Blue Bottle", "Shibuya, Tokyo")
                .doesNotContain("35.6", "139.7", "provider_place_id");
        org.mockito.ArgumentCaptor<Map<String, Object>> request = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(gateway).callWithActionId(any(), eq("places_search"), request.capture(), any());
        assertThat(request.getValue()).containsEntry("query", "Blue Bottle").containsEntry("near", "here");
    }

    @Test
    void deviceUnavailableIsAUsefulToolError() throws Exception {
        DeviceToolGateway gateway = mock(DeviceToolGateway.class);
        when(gateway.callWithActionId(any(), eq("places_search"), any(), any()))
                .thenThrow(new DeviceToolGateway.DeviceUnavailableException());
        ToolResult result = new PlacesSearchTool(gateway).handle(
                JSON.readTree("{\"query\":\"Blue Bottle\",\"near\":\"Shibuya\"}"), context(false));

        assertThat(result).isEqualTo(new ToolResult.Err("device_unavailable",
                "Place search needs TinyMe open on your iPhone"));
    }

    @Test
    void nearbyToolDoesNotAskTheModelForCoordinatesAndRequiresPhoneLocation() throws Exception {
        var service = mock(com.tinyme.domain.places.service.PlaceService.class);
        ToolResult result = new PlacesNearTool(service).handle(JSON.readTree("{}"), context(false));
        assertThat(result).isEqualTo(new ToolResult.Err("no_location",
                "Turn on location for TinyMe, or tell me where you are"));
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    private static ToolContext context(boolean withLocation) {
        TurnListener listener = new TurnListener() {
            @Override public void on(TurnEvent event) { }
            @Override public boolean supportsDeviceActions() { return true; }
        };
        TurnLocation location = withLocation
                ? new TurnLocation(35.6, 139.7, 20, Instant.parse("2026-10-15T03:00:00Z"), "Shibuya") : null;
        return new ToolContext(UUID.randomUUID(), UUID.randomUUID(), ZoneId.of("Asia/Tokyo"), LocalDate.now(),
                Instant.parse("2026-10-15T03:00:00Z"), null, location, listener);
    }
}
