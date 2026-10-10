package com.tinyme.conversation.controller;

import com.tinyme.agent.model.SessionRef;
import com.tinyme.agent.model.TurnEvent;
import com.tinyme.agent.model.TurnEvent;
import com.tinyme.agent.model.TurnResult;
import com.tinyme.agent.service.TurnListener;
import com.tinyme.conversation.model.ConversationMessageRequest;
import com.tinyme.conversation.service.ConversationOperations;
import com.tinyme.conversation.service.ConversationService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    void returnsConflictForDuplicateWithoutStartingTurn() throws Exception {
        UUID existingId = UUID.randomUUID();
        service.acceptance = new ConversationService.Duplicate(existingId);

        mvc.perform(post("/conversations/today/messages").contentType("application/json").content("""
                {"client_msg_id":"%s","text":"had a coffee"}
                """.formatted(UUID.randomUUID())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message_id").value(existingId.toString()));

        assertThat(service.runCalled).isFalse();
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
        }

        @Override
        public ConversationService.Acceptance accept(ConversationMessageRequest request) {
            this.request = request;
            acceptCalled = true;
            return acceptance;
        }

        @Override
        public CompletableFuture<TurnResult> runAsync(ConversationService.Accepted accepted, TurnListener listener) {
            runCalled = true;
            listener.on(new TurnEvent.ActionDone("entries_add", "Logged drink", false));
            listener.on(new TurnEvent.Text("Logged coffee."));
            if (emitFailure) listener.on(new TurnEvent.Failed("agent_error", "agent failed", true));
            return failure == null ? CompletableFuture.completedFuture(result) : CompletableFuture.failedFuture(failure);
        }
    }
}
