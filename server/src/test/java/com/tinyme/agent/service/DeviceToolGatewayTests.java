package com.tinyme.agent.service;

import com.tinyme.agent.model.DeviceToolResult;
import com.tinyme.agent.model.TurnEvent;
import com.tinyme.agent.support.AgentDatabaseConfiguration;
import com.tinyme.auth.service.DeviceTokenService;
import com.tinyme.tools.model.ToolContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "tinyme.agent.setup-enabled=false",
        "TINYME_OWNER_EMAIL=owner@test.tinyme.local",
        "TINYME_OWNER_PASSWORD=test-only-owner-password"
})
@AutoConfigureMockMvc
@Import(AgentDatabaseConfiguration.class)
class DeviceToolGatewayTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired DeviceToolGateway gateway;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired DeviceTokenService tokens;

    @BeforeEach
    void clearActions() {
        jdbc.update("DELETE FROM pending_actions");
    }

    @Test
    void phoneResultCompletesTheWaitingTurnAndDuplicatePostsConflict() throws Exception {
        var events = new LinkedBlockingQueue<TurnEvent>();
        var context = context(event -> events.add(event));
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<DeviceToolResult> waiting = executor.submit(
                    (java.util.concurrent.Callable<DeviceToolResult>) () -> gateway.call(context, "places_search",
                            Map.of("query", "Blue Bottle Shibuya", "near", "here"), Duration.ofSeconds(5)));
            TurnEvent event = events.poll(2, TimeUnit.SECONDS);
            assertThat(event).isInstanceOf(TurnEvent.ClientAction.class);
            TurnEvent.ClientAction action = (TurnEvent.ClientAction) event;
            assertThat(action.tool()).isEqualTo("places_search");
            assertThat(action.input()).containsEntry("query", "Blue Bottle Shibuya");
            assertThat(jdbc.queryForObject("SELECT status FROM pending_actions WHERE id = ?", String.class,
                    action.actionId())).isEqualTo("delivered");

            String token = tokens.issue("device action test").token();
            mvc.perform(post("/actions/{id}/result", action.actionId())
                            .header("Authorization", "Bearer " + token)
                            .contentType("application/json")
                            .content("{\"ok\":true,\"data\":[{\"name\":\"Blue Bottle Shibuya\",\"lat\":35.66}] }"))
                    .andExpect(status().isNoContent());

            DeviceToolResult result = waiting.get(2, TimeUnit.SECONDS);
            assertThat(result.succeeded()).isTrue();
            assertThat(result.data().get(0).get("name").stringValue()).isEqualTo("Blue Bottle Shibuya");
            assertThat(jdbc.queryForObject("SELECT status FROM pending_actions WHERE id = ?", String.class,
                    action.actionId())).isEqualTo("succeeded");
            assertThat(jdbc.queryForObject("SELECT expires_at > statement_timestamp() + INTERVAL '1 hour' "
                            + "FROM pending_actions WHERE id = ?", Boolean.class, action.actionId()))
                    .isTrue();
            mvc.perform(post("/actions/{id}/result", action.actionId())
                            .header("Authorization", "Bearer " + token)
                            .contentType("application/json")
                            .content("{\"ok\":true,\"data\":[]}"))
                    .andExpect(status().isConflict());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void unansweredActionExpiresAndUnknownActionIsNotFound() {
        assertThatThrownBy(() -> gateway.call(context(event -> { }), "places_search",
                Map.of("query", "nearby"), Duration.ofMillis(80)))
                .isInstanceOf(DeviceToolGateway.DeviceUnavailableException.class);
        UUID expiredId = jdbc.queryForObject("SELECT id FROM pending_actions ORDER BY created_at DESC LIMIT 1", UUID.class);
        assertThat(jdbc.queryForObject("SELECT status FROM pending_actions WHERE id = ?", String.class, expiredId))
                .isEqualTo("expired");

        assertThatThrownBy(() -> gateway.submitResult(UUID.randomUUID(), DeviceToolResult.success(JSON.readTree("[]"))))
                .isInstanceOf(DeviceToolGateway.DeviceActionNotFoundException.class);
    }

    @Test
    void actionWithoutLivePhoneFailsImmediatelyAndDoesNotCreatePendingRow() {
        ToolContext noLivePhone = new ToolContext(UUID.randomUUID(), null, ZoneId.of("Asia/Tokyo"),
                LocalDate.now(), Instant.now(), null, null, TurnListener.NONE);

        assertThatThrownBy(() -> gateway.call(noLivePhone, "places_search", Map.of("query", "nearby"),
                Duration.ofSeconds(20)))
                .isInstanceOf(DeviceToolGateway.DeviceUnavailableException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pending_actions", Integer.class)).isZero();
    }

    @Test
    void actionsEndpointRequiresAnOwnerTokenAndRejectsMalformedResult() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(post("/actions/{id}/result", id).contentType("application/json")
                        .content("{\"ok\":true,\"data\":[]}"))
                .andExpect(status().isUnauthorized());

        String token = tokens.issue("device action test").token();
        mvc.perform(post("/actions/{id}/result", id)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"ok\":\"yes\"}"))
                .andExpect(status().isBadRequest());
    }

    private static ToolContext context(TurnListener listener) {
        TurnListener livePhoneListener = new TurnListener() {
            @Override
            public void on(TurnEvent event) {
                listener.on(event);
            }

            @Override
            public boolean supportsDeviceActions() {
                return true;
            }
        };
        return new ToolContext(UUID.randomUUID(), null, ZoneId.of("Asia/Tokyo"), LocalDate.now(),
                Instant.now(), null, null, livePhoneListener);
    }
}
