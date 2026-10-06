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
                "entries_add", fixture.input)).thenReturn(callId);
        RegisteredTool registration = new RegisteredTool(spec(), fixture.handler);
        when(fixture.registry.find("entries_add")).thenReturn(Optional.of(registration));
        when(fixture.validator.validate("entries_add", fixture.input)).thenReturn(List.of());
        when(fixture.handler.handle(eq(fixture.input), any(ToolContext.class)))
                .thenReturn(new ToolResult.Ok("entry-1", "Added drink"));

        DispatchOutcome outcome = fixture.dispatcher().dispatch(
                fixture.eventId, "entries_add", fixture.input, fixture.base);

        assertThat(outcome.isError()).isFalse();
        JsonNode envelope = JSON.readTree(outcome.resultJson());
        assertThat(envelope.get("ok").booleanValue()).isTrue();
        assertThat(envelope.get("data").stringValue()).isEqualTo("entry-1");
        assertThat(envelope.get("summary").stringValue()).isEqualTo("Added drink");

        var context = org.mockito.ArgumentCaptor.forClass(ToolContext.class);
        verify(fixture.handler).handle(eq(fixture.input), context.capture());
        assertThat(context.getValue().toolCallId()).isEqualTo(callId);
        verify(fixture.calls).finish(eq(callId), any(JsonNode.class), eq(false), eq("done"));
    }

    @Test
    void replaysStoredFinishedResultWithoutRunningToolAgain() {
        Fixture fixture = new Fixture();
        JsonNode savedResult = JSON.readTree("""
                {"ok":true,"data":{"id":"entry-1"},"summary":"Saved"}
                """);
        when(fixture.calls.findByEventId(fixture.eventId)).thenReturn(Optional.of(
                storedCall("done", savedResult, false)));

        DispatchOutcome outcome = fixture.dispatcher().dispatch(
                fixture.eventId, "entries_add", fixture.input, fixture.base);

        assertThat(JSON.readTree(outcome.resultJson())).isEqualTo(savedResult);
        assertThat(outcome.isError()).isFalse();
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
                "entries_add", fixture.input)).thenThrow(new DuplicateKeyException("duplicate"));

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
                "entries_add", fixture.input)).thenThrow(new DuplicateKeyException("duplicate"));

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
            when(calls.insertRunning(sessionId, eventId, toolName, input)).thenReturn(callId);
        }

        private ToolDispatcher dispatcher() {
            return new ToolDispatcher(registry, validator, calls);
        }
    }
}
