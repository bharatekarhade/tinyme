package com.tinyme.tools.service;

import com.tinyme.tools.model.DispatchOutcome;
import com.tinyme.tools.model.RegisteredTool;
import com.tinyme.tools.model.StoredCall;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolHandler;
import com.tinyme.tools.model.ToolResult;
import com.tinyme.tools.model.ToolSpec;
import com.tinyme.tools.repository.ToolCallRepository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.read.ListAppender;


import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ToolDispatcherTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void storesSuccessAndPassesDatabaseIdIntoContext() {
        Fixture fixture = new Fixture();
        UUID callId = UUID.randomUUID();
        when(fixture.calls.findByEventId(fixture.eventId)).thenReturn(Optional.empty());
        when(fixture.calls.insertRunning(fixture.base.sessionRowId(), fixture.eventId,
                "entries_add", fixture.input, "tinyme-chat", null)).thenReturn(callId);
        RegisteredTool registration = new RegisteredTool(spec(), fixture.handler);
        when(fixture.registry.find("entries_add")).thenReturn(Optional.of(registration));
        when(fixture.validator.validate("entries_add", fixture.input)).thenReturn(List.of());
        when(fixture.handler.handle(eq(fixture.input), any(ToolContext.class)))
                .thenReturn(new ToolResult.Ok("entry-1", "Added drink", Map.of("card_id", "card-1")));

        DispatchOutcome outcome = fixture.dispatcher().dispatch(
                fixture.eventId, "entries_add", fixture.input, fixture.base);

        assertThat(outcome.isError()).isFalse();
        JsonNode envelope = JSON.readTree(outcome.resultJson());
        assertThat(envelope.get("ok").booleanValue()).isTrue();
        assertThat(envelope.get("data").stringValue()).isEqualTo("entry-1");
        assertThat(envelope.get("summary").stringValue()).isEqualTo("Added drink");
        assertThat(envelope.has("ref")).as("ref is for the phone, never the model").isFalse();
        assertThat(outcome.ref()).containsEntry("card_id", "card-1");

        var context = org.mockito.ArgumentCaptor.forClass(ToolContext.class);
        verify(fixture.handler).handle(eq(fixture.input), context.capture());
        assertThat(context.getValue().toolCallId()).isEqualTo(callId);
        var stored = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
        verify(fixture.calls).finish(eq(callId), stored.capture(), eq(false), eq("done"));
        assertThat(stored.getValue().get("ref").get("card_id").stringValue()).isEqualTo("card-1");
    }

    @Test
    void refusesToolsOutsideTheCallingAgentsAllowList() {
        Fixture fixture = new Fixture();
        fixture.startNewCall();
        var restricted = new com.tinyme.agent.config.AgentToolAllowLists();
        restricted.setToolAllowLists(Map.of("tinyme-gmail", List.of("gmail_search")));
        var dispatcher = new ToolDispatcher(fixture.registry, fixture.validator, fixture.calls, restricted);
        when(fixture.calls.insertRunning(fixture.base.sessionRowId(), fixture.eventId,
                "entries_add", fixture.input, "tinyme-gmail", "thread-1")).thenReturn(fixture.callId);
        var registration = new RegisteredTool(spec(), fixture.handler);
        when(fixture.registry.find("entries_add")).thenReturn(Optional.of(registration));
        var context = fixture.base.withAgent("tinyme-gmail", "thread-1");

        DispatchOutcome outcome = dispatcher.dispatch(fixture.eventId, "entries_add", fixture.input, context);

        assertError(outcome, "tool_not_allowed");
        verify(fixture.calls).insertRunning(fixture.base.sessionRowId(), fixture.eventId,
                "entries_add", fixture.input, "tinyme-gmail", "thread-1");
        verifyNoInteractions(fixture.validator);
        verify(fixture.handler, never()).handle(any(), any());
    }

    @Test
    void noLocationNearbyErrorCarriesPromptMarkerAndPersistsItForReplay() {
        Fixture fixture = new Fixture();
        fixture.startNewCall("places_near");
        when(fixture.handler.name()).thenReturn("places_near");
        RegisteredTool placesNear = new RegisteredTool(
                new ToolSpec("places_near", JSON.readTree("{\"type\":\"object\"}")), fixture.handler);
        when(fixture.registry.find("places_near")).thenReturn(Optional.of(placesNear));
        when(fixture.validator.validate("places_near", fixture.input)).thenReturn(List.of());
        when(fixture.handler.handle(eq(fixture.input), any(ToolContext.class)))
                .thenReturn(new ToolResult.Err("no_location", "Turn on location"));

        DispatchOutcome outcome = fixture.dispatcher().dispatch(
                fixture.eventId, "places_near", fixture.input, fixture.base);

        assertThat(outcome.isError()).isTrue();
        assertThat(outcome.ref()).containsEntry("error_code", "no_location");
        assertThat(JSON.readTree(outcome.resultJson()).has("ref")).isFalse();
        var stored = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
        verify(fixture.calls).finish(any(), stored.capture(), eq(true), eq("done"));
        JsonNode saved = stored.getValue();
        assertThat(saved.get("ref").get("error_code").stringValue()).isEqualTo("no_location");

        when(fixture.calls.findByEventId(fixture.eventId)).thenReturn(Optional.of(
                storedCall("done", saved, true)));
        DispatchOutcome replay = fixture.dispatcher().dispatch(
                fixture.eventId, "places_near", fixture.input, fixture.base);
        assertThat(replay.ref()).containsEntry("error_code", "no_location");
    }

    @Test
    void replaysStoredFinishedResultWithoutRunningToolAgain() {
        Fixture fixture = new Fixture();
        JsonNode savedResult = JSON.readTree("""
                {"ok":true,"data":{"id":"entry-1"},"summary":"Saved","ref":{"card_id":"card-1"}}
                """);
        when(fixture.calls.findByEventId(fixture.eventId)).thenReturn(Optional.of(
                storedCall("done", savedResult, false)));

        DispatchOutcome outcome = fixture.dispatcher().dispatch(
                fixture.eventId, "entries_add", fixture.input, fixture.base);

        JsonNode replayed = JSON.readTree(outcome.resultJson());
        assertThat(replayed.get("data").get("id").stringValue()).isEqualTo("entry-1");
        assertThat(replayed.has("ref")).as("replays strip ref too").isFalse();
        assertThat(outcome.isError()).isFalse();
        assertThat(outcome.ref()).containsEntry("card_id", "card-1");
        verify(fixture.calls, never()).insertRunning(any(), any(), any(), any());
        verifyNoInteractions(fixture.registry, fixture.validator, fixture.handler);
    }

    @Test
    void returnsAlreadyRunningForAnExistingRunningCall() {
        Fixture fixture = new Fixture();
        when(fixture.calls.findByEventId(fixture.eventId)).thenReturn(Optional.of(
                storedCall("running", null, false)));

        DispatchOutcome outcome = fixture.dispatcher().dispatch(
                fixture.eventId, "entries_add", fixture.input, fixture.base);

        assertError(outcome, "already_running");
        verify(fixture.calls, never()).insertRunning(any(), any(), any(), any());
        verifyNoInteractions(fixture.registry, fixture.validator, fixture.handler);
    }

    @Test
    void duplicateInsertChecksRowAndReturnsAlreadyRunningWhenStillRunning() {
        Fixture fixture = new Fixture();
        when(fixture.calls.findByEventId(fixture.eventId)).thenReturn(
                Optional.empty(), Optional.of(storedCall("running", null, false)));
        when(fixture.calls.insertRunning(fixture.base.sessionRowId(), fixture.eventId,
                "entries_add", fixture.input, "tinyme-chat", null)).thenThrow(new DuplicateKeyException("duplicate"));

        DispatchOutcome outcome = fixture.dispatcher().dispatch(
                fixture.eventId, "entries_add", fixture.input, fixture.base);

        assertError(outcome, "already_running");
        verifyNoInteractions(fixture.registry, fixture.validator, fixture.handler);
    }

    @Test
    void duplicateInsertReturnsFinishedRowResult() {
        Fixture fixture = new Fixture();
        JsonNode savedResult = JSON.readTree("{\"ok\":false,\"error\":{\"code\":\"validation_error\",\"message\":\"bad\"}}");
        when(fixture.calls.findByEventId(fixture.eventId)).thenReturn(
                Optional.empty(), Optional.of(storedCall("done", savedResult, true)));
        when(fixture.calls.insertRunning(fixture.base.sessionRowId(), fixture.eventId,
                "entries_add", fixture.input, "tinyme-chat", null)).thenThrow(new DuplicateKeyException("duplicate"));

        DispatchOutcome outcome = fixture.dispatcher().dispatch(
                fixture.eventId, "entries_add", fixture.input, fixture.base);

        assertThat(JSON.readTree(outcome.resultJson())).isEqualTo(savedResult);
        assertThat(outcome.isError()).isTrue();
        verifyNoInteractions(fixture.registry, fixture.validator, fixture.handler);
    }

    @Test
    void distinguishesUnknownFromNotImplementedTools() {
        Fixture unknown = new Fixture();
        unknown.startNewCall("invented_tool");
        when(unknown.registry.find("invented_tool")).thenReturn(Optional.empty());

        DispatchOutcome unknownOutcome = unknown.dispatcher().dispatch(
                unknown.eventId, "invented_tool", unknown.input, unknown.base);
        assertError(unknownOutcome, "unknown_tool");
        verify(unknown.calls).finish(eq(unknown.callId), any(JsonNode.class), eq(true), eq("done"));
        verifyNoInteractions(unknown.validator, unknown.handler);

        Fixture notImplemented = new Fixture();
        notImplemented.startNewCall();
        when(notImplemented.registry.find("entries_add"))
                .thenReturn(Optional.of(new RegisteredTool(spec(), null)));

        DispatchOutcome notImplementedOutcome = notImplemented.dispatcher().dispatch(
                notImplemented.eventId, "entries_add", notImplemented.input, notImplemented.base);
        assertError(notImplementedOutcome, "not_implemented");
        verify(notImplemented.calls).finish(
                eq(notImplemented.callId), any(JsonNode.class), eq(true), eq("done"));
        verifyNoInteractions(notImplemented.validator, notImplemented.handler);
    }

    @Test
    void returnsOnlyFirstFiveValidationErrors() {
        Fixture fixture = new Fixture();
        fixture.startNewCall();
        RegisteredTool registration = new RegisteredTool(spec(), fixture.handler);
        when(fixture.registry.find("entries_add")).thenReturn(Optional.of(registration));
        when(fixture.validator.validate("entries_add", fixture.input)).thenReturn(
                List.of("e1", "e2", "e3", "e4", "e5", "e6"));

        DispatchOutcome outcome = fixture.dispatcher().dispatch(
                fixture.eventId, "entries_add", fixture.input, fixture.base);

        JsonNode envelope = JSON.readTree(outcome.resultJson());
        assertThat(envelope.get("error").get("code").stringValue()).isEqualTo("validation_error");
        assertThat(envelope.get("error").get("message").stringValue()).isEqualTo("e1; e2; e3; e4; e5");
        verify(fixture.handler, never()).handle(any(), any());
        verify(fixture.calls).finish(eq(fixture.callId), any(JsonNode.class), eq(true), eq("done"));
    }

    @Test
    void handlerFailureIsStoredAsInternalErrorAndFailedStatus() {
        Fixture fixture = new Fixture();
        fixture.startNewCall();
        RegisteredTool registration = new RegisteredTool(spec(), fixture.handler);
        when(fixture.registry.find("entries_add")).thenReturn(Optional.of(registration));
        when(fixture.validator.validate("entries_add", fixture.input)).thenReturn(List.of());
        var failure = new IllegalStateException("sensitive handler detail");
        when(fixture.handler.handle(eq(fixture.input), any(ToolContext.class))).thenThrow(failure);

        Logger logger = (Logger) LoggerFactory.getLogger(ToolDispatcher.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        DispatchOutcome outcome;
        try {
            outcome = fixture.dispatcher().dispatch(
                    fixture.eventId, "entries_add", fixture.input, fixture.base);
            assertThat(appender.list).filteredOn(event -> event.getLevel() == Level.ERROR)
                    .singleElement().satisfies(event -> {
                        assertThat(event.getFormattedMessage())
                                .contains("tool=entries_add", "eventId=" + fixture.eventId, "code=internal_error")
                                .doesNotContain(fixture.input.toString());
                        assertThat(((ThrowableProxy) event.getThrowableProxy()).getThrowable()).isSameAs(failure);
                    });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertError(outcome, "internal_error");
        assertThat(outcome.resultJson()).doesNotContain("sensitive handler detail");
        verify(fixture.calls).finish(eq(fixture.callId), any(JsonNode.class), eq(true), eq("failed"));
    }

    @Test
    void intentionalToolErrorsAreDoneRatherThanFailed() {
        Fixture fixture = new Fixture();
        fixture.startNewCall();
        RegisteredTool registration = new RegisteredTool(spec(), fixture.handler);
        when(fixture.registry.find("entries_add")).thenReturn(Optional.of(registration));
        when(fixture.validator.validate("entries_add", fixture.input)).thenReturn(List.of());
        when(fixture.handler.handle(eq(fixture.input), any(ToolContext.class)))
                .thenReturn(new ToolResult.Err("conflict", "No change made"));

        DispatchOutcome outcome = fixture.dispatcher().dispatch(
                fixture.eventId, "entries_add", fixture.input, fixture.base);

        assertError(outcome, "conflict");
        verify(fixture.calls).finish(eq(fixture.callId), any(JsonNode.class), eq(true), eq("done"));
    }

    private static void assertError(DispatchOutcome outcome, String code) {
        assertThat(outcome.isError()).isTrue();
        JsonNode envelope = JSON.readTree(outcome.resultJson());
        assertThat(envelope.get("ok").booleanValue()).isFalse();
        assertThat(envelope.get("error").get("code").stringValue()).isEqualTo(code);
    }

    private static ToolSpec spec() {
        return new ToolSpec("entries_add", JSON.readTree("{\"type\":\"object\"}"));
    }

    private static StoredCall storedCall(String status, JsonNode result, boolean isError) {
        return new StoredCall(UUID.randomUUID(), UUID.randomUUID(), "event-1", "entries_add",
                JSON.readTree("{}"), result, isError, status, Instant.now(), null);
    }

    private static final class Fixture {
        private final ToolRegistry registry = mock(ToolRegistry.class);
        private final ToolValidator validator = mock(ToolValidator.class);
        private final ToolCallRepository calls = mock(ToolCallRepository.class);
        private final ToolHandler handler = mock(ToolHandler.class);
        private final UUID sessionId = UUID.randomUUID();
        private final String eventId = "event-1";
        private final JsonNode input = JSON.readTree("{\"kind\":\"drink\"}");
        private final ToolContext base = new ToolContext(sessionId, null, ZoneId.of("UTC"),
                LocalDate.of(2026, 10, 5), Instant.parse("2026-10-05T12:00:00Z"));
        private UUID callId;

        private Fixture() {
            when(handler.name()).thenReturn("entries_add");
        }

        private void startNewCall() {
            startNewCall("entries_add");
        }

        private void startNewCall(String toolName) {
            callId = UUID.randomUUID();
            when(calls.findByEventId(eventId)).thenReturn(Optional.empty());
            when(calls.insertRunning(sessionId, eventId, toolName, input, "tinyme-chat", null)).thenReturn(callId);
        }

        private ToolDispatcher dispatcher() {
            return new ToolDispatcher(registry, validator, calls);
        }
    }
}
