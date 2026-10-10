package com.tinyme.conversation.controller;

import com.tinyme.agent.model.SessionRef;
import com.tinyme.agent.model.TurnEvent;
import com.tinyme.agent.model.TurnResult;
import com.tinyme.agent.service.TurnListener;
import com.tinyme.conversation.model.ConversationMessageRequest;
import com.tinyme.conversation.model.ConversationHistory;
import com.tinyme.conversation.model.ConversationHistoryMessage;
import com.tinyme.conversation.service.ConversationOperations;
import com.tinyme.conversation.service.ConversationService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.ZoneId;
import java.time.Instant;
import java.time.LocalDate;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ConversationControllerTests {
    private static final LocalValidatorFactoryBean VALIDATOR = validator();

    private final RecordingConversationService service = new RecordingConversationService();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service.reset();
        mvc = standaloneSetup(new ConversationController(service)).setValidator(VALIDATOR).build();
    }

    @AfterAll
    static void closeValidator() {
        VALIDATOR.close();
    }

    @Test
    void validatesRequiredUuidTextLengthAndZone() throws Exception {
        mvc.perform(post("/conversations/today/messages").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/conversations/today/messages").contentType("application/json").content("""
                {"client_msg_id":"not-a-uuid","text":"hi","client_context":{"tz":"Asia/Tokyo"}}
                """))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/conversations/today/messages").contentType("application/json").content("""
                {"client_msg_id":"%s","text":"","client_context":{"tz":"Asia/Tokyo"}}
                """.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/conversations/today/messages").contentType("application/json").content("""
                {"client_msg_id":"%s","text":"hi","client_context":{"tz":"Not/A_Zone"}}
                """.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest());

        assertThat(service.acceptCalled).isFalse();
    }

    @Test
    void acceptsMessageThenStartsTurnAndReturnsEmitter() throws Exception {
        UUID clientMessageId = UUID.randomUUID();
        UUID assistantMessageId = UUID.randomUUID();
        service.result = new TurnResult("Logged coffee.", 1, assistantMessageId);
        MvcResult response = mvc.perform(post("/conversations/today/messages").contentType("application/json").content("""
                {"client_msg_id":"%s","text":"had a coffee","client_context":{"tz":"Asia/Tokyo"}}
                """.formatted(clientMessageId)))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        String sse = mvc.perform(asyncDispatch(response))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/event-stream"))
                .andReturn().getResponse().getContentAsString();
        assertThat(sse.indexOf("\"type\":\"turn_started\"")).isGreaterThanOrEqualTo(0);
        assertThat(sse.indexOf("\"type\":\"turn_started\"")).isLessThan(sse.indexOf("\"type\":\"action_done\""));
        assertThat(sse.indexOf("\"type\":\"action_done\"")).isLessThan(sse.indexOf("\"type\":\"text\""));
        assertThat(sse.indexOf("\"type\":\"text\"")).isLessThan(sse.indexOf("\"type\":\"done\""));
        assertThat(sse).contains("\"type\":\"done\"", "\"reply_text\":\"Logged coffee.\"",
                "\"tool_calls\":1", "\"assistant_message_id\":\"" + assistantMessageId + "\"");
        assertThat(sse.lines().filter(line -> line.startsWith("data:")).count()).isEqualTo(4);

        assertThat(service.acceptCalled).isTrue();
        assertThat(service.runCalled).isTrue();
        assertThat(service.request.clientMessageId()).isEqualTo(clientMessageId);
        assertThat(service.request.text()).isEqualTo("had a coffee");
        assertThat(service.request.clientContext().tz()).isEqualTo("Asia/Tokyo");
    }

    @Test
    void emitsDoneAfterAnErrorWithoutAssistantMessageId() throws Exception {
        service.failure = new IllegalStateException("test failure");
        service.emitFailure = true;
        MvcResult response = mvc.perform(post("/conversations/today/messages").contentType("application/json").content("""
                {"client_msg_id":"%s","text":"had a coffee"}
                """.formatted(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        String sse = mvc.perform(asyncDispatch(response))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(sse.indexOf("\"type\":\"failed\"")).isGreaterThanOrEqualTo(0);
        assertThat(sse.indexOf("\"type\":\"failed\"")).isLessThan(sse.indexOf("\"type\":\"done\""));
        assertThat(sse).doesNotContain("assistant_message_id");
    }

    @Test
    void includesSavedPartialAssistantIdInDoneAfterAnError() throws Exception {
        UUID partialId = UUID.randomUUID();
        service.failure = new IllegalStateException("test failure");
        service.emitFailure = true;
        service.failureAssistantId = partialId;
        MvcResult response = mvc.perform(post("/conversations/today/messages").contentType("application/json").content("""
                {"client_msg_id":"%s","text":"had a coffee"}
                """.formatted(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        String sse = mvc.perform(asyncDispatch(response)).andReturn().getResponse().getContentAsString();
        assertThat(sse).contains("\"type\":\"done\"", "\"assistant_message_id\":\"" + partialId + "\"");
    }

    @Test
    void returnsConflictForDuplicateWithoutStartingTurn() throws Exception {
        UUID clientMessageId = UUID.randomUUID();
        UUID existingId = UUID.randomUUID();
        service.duplicateClientMessageId = clientMessageId;
        service.duplicateMessageId = existingId;

        mvc.perform(post("/conversations/today/messages").contentType("application/json").content("""
                {"client_msg_id":"%s","text":"had a coffee"}
                """.formatted(clientMessageId)))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());

        mvc.perform(post("/conversations/today/messages").contentType("application/json").content("""
                {"client_msg_id":"%s","text":"had a coffee"}
                """.formatted(clientMessageId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message_id").value(existingId.toString()));

        assertThat(service.runCount).isEqualTo(1);
    }

    @Test
    void historyReturnsBothRolesLowercaseWithActionsAndRejectsBadDay() throws Exception {
        service.history = new ConversationHistory(List.of(
                new ConversationHistoryMessage(UUID.randomUUID(), "USER", "had a coffee", List.of(),
                        Instant.parse("2026-10-10T00:01:00Z")),
                new ConversationHistoryMessage(UUID.randomUUID(), "ASSISTANT", "Logged coffee.",
                        List.of(java.util.Map.of("tool", "entries_add", "summary", "Logged drink", "is_error", false)),
                        Instant.parse("2026-10-10T00:01:02Z"))));

        mvc.perform(get("/conversations").param("day", "2026-10-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].content").value("had a coffee"))
                .andExpect(jsonPath("$.messages[1].role").value("assistant"))
                .andExpect(jsonPath("$.messages[1].actions[0].tool").value("entries_add"))
                .andExpect(jsonPath("$.messages[1].created_at").value("2026-10-10T00:01:02Z"));
        assertThat(service.historyDay).isEqualTo(LocalDate.of(2026, 10, 10));

        mvc.perform(get("/conversations").param("day", "not-a-date"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingHistoryDayIsDelegatedAsNullForUsersConfiguredToday() throws Exception {
        mvc.perform(get("/conversations")).andExpect(status().isOk());
        assertThat(service.historyDay).isNull();
    }

    @Test
    void simulatedClientDisconnectDoesNotStopToolOrAssistantPersistence() throws Exception {
        service.persistEffects = true;
        var controller = new ConversationController(service) {
            @Override
            protected SseEmitter createEmitter() {
                return new SseEmitter() {
                    private int writes;

                    @Override
                    public void send(SseEmitter.SseEventBuilder builder) throws IOException {
                        if (++writes == 2) throw new IOException("simulated client disconnect");
                    }
                };
            }
        };

        ResponseEntity<?> response = controller.message(new ConversationMessageRequest(
                UUID.randomUUID(), "had a coffee", new ConversationMessageRequest.ClientContext("Asia/Tokyo")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(service.entryCreated).isTrue();
        assertThat(service.assistantStored).isTrue();
        assertThat(service.runCount).isEqualTo(1);
    }

    private static LocalValidatorFactoryBean validator() {
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return validator;
    }

    private static final class RecordingConversationService implements ConversationOperations {
        private ConversationMessageRequest request;
        private ConversationService.Acceptance acceptance;
        private boolean acceptCalled;
        private boolean runCalled;
        private TurnResult result = new TurnResult("Logged coffee.", 1);
        private Throwable failure;
        private boolean emitFailure;
        private UUID failureAssistantId;
        private UUID duplicateClientMessageId;
        private UUID duplicateMessageId;
        private boolean duplicateSeen;
        private int runCount;
        private boolean persistEffects;
        private boolean entryCreated;
        private boolean assistantStored;
        private ConversationHistory history = new ConversationHistory(List.of());
        private LocalDate historyDay;

        private RecordingConversationService() { reset(); }

        private void reset() {
            request = null;
            acceptance = new ConversationService.Accepted(
                    new SessionRef(UUID.randomUUID(), "sesn_test"), UUID.randomUUID(),
                    "had a coffee", ZoneId.of("Asia/Tokyo"));
            acceptCalled = false;
            runCalled = false;
            result = new TurnResult("Logged coffee.", 1);
            failure = null;
            emitFailure = false;
            failureAssistantId = null;
            duplicateClientMessageId = null;
            duplicateMessageId = null;
            duplicateSeen = false;
            runCount = 0;
            persistEffects = false;
            entryCreated = false;
            assistantStored = false;
            history = new ConversationHistory(List.of());
            historyDay = null;
        }

        @Override
        public ConversationService.Acceptance accept(ConversationMessageRequest request) {
            this.request = request;
            acceptCalled = true;
            if (duplicateClientMessageId != null && duplicateClientMessageId.equals(request.clientMessageId())) {
                if (duplicateSeen) return new ConversationService.Duplicate(duplicateMessageId);
                duplicateSeen = true;
            }
            return acceptance;
        }

        @Override
        public CompletableFuture<TurnResult> runAsync(ConversationService.Accepted accepted, TurnListener listener) {
            runCalled = true;
            runCount++;
            if (persistEffects) entryCreated = true;
            listener.on(new TurnEvent.ActionDone("entries_add", "Logged drink", false));
            listener.on(new TurnEvent.Text("Logged coffee."));
            if (persistEffects) assistantStored = true;
            if (emitFailure) listener.on(new TurnEvent.Failed(
                    "agent_error", "agent failed", true, failureAssistantId));
            return failure == null ? CompletableFuture.completedFuture(result) : CompletableFuture.failedFuture(failure);
        }

        @Override
        public ConversationHistory history(LocalDate day) {
            historyDay = day;
            return history;
        }
    }
}
