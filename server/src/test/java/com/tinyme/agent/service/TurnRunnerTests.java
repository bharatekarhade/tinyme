package com.tinyme.agent.service;

import com.tinyme.agent.client.EventStream;
import com.tinyme.agent.client.ManagedAgentApi;
import com.tinyme.agent.model.SessionRef;
import com.tinyme.agent.model.TurnResult;
import com.tinyme.tools.model.DispatchOutcome;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.service.ToolDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TurnRunnerTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");
    private static final Instant NOW = Instant.parse("2026-10-05T15:30:00Z");
    private static final String END = """
            {"type":"session.status_idle","id":"end","stop_reason":{"type":"end_turn"}}
            """;
    private final SessionManager sessions = mock(SessionManager.class);
    private final ContextPrefixBuilder prefix = mock(ContextPrefixBuilder.class);
    private final ManagedAgentApi api = mock(ManagedAgentApi.class);
    private final ToolDispatcher dispatcher = mock(ToolDispatcher.class);
    private final SessionRef session = new SessionRef(UUID.randomUUID(), "sesn_test");

    private TurnRunner runner(Duration timeout) throws Exception {
        when(sessions.todaySession(ZONE)).thenReturn(session);
        when(prefix.build(ZONE)).thenReturn("[context]\ntz: Asia/Tokyo\n[/context]");
        return new TurnRunner(sessions, prefix, api, dispatcher, Clock.fixed(NOW, ZoneOffset.UTC), timeout);
    }

    @Test
    void opensBeforeSendingTwoTextBlocksAndAppendsOnlyUniqueMessageText() throws Exception {
        var runner = runner(Duration.ofSeconds(5));
        String message = """
                {"type":"agent.message","id":"msg1","content":[
                  {"type":"thinking","thinking":"hidden"},{"type":"text","text":"Hello "},
                  {"type":"text","text":"there."}]}
                """;
        InputStream body = events(message, message,
                "{\"type\":\"agent.message\",\"id\":\"msg2\",\"content\":[{\"type\":\"text\",\"text\":\" Welcome!\"}]}", END);
        var stream = new EventStream(body);
        when(api.openStream("sesn_test")).thenReturn(stream);

        assertThat(runner.run("hello", ZONE)).isEqualTo(new TurnResult("Hello there. Welcome!", 0));

        var order = inOrder(sessions, prefix, api);
        order.verify(sessions).todaySession(ZONE);
        order.verify(prefix).build(ZONE);
        order.verify(api).openStream("sesn_test");
        order.verify(api).sendEvents("sesn_test", List.of(Map.of("type", "user.message", "content", List.of(
                Map.of("type", "text", "text", "[context]\ntz: Asia/Tokyo\n[/context]"),
                Map.of("type", "text", "text", "hello")))));
        verifyNoMoreInteractions(api);
        verifyNoInteractions(dispatcher);
        verify(body).close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void dispatchesOnceAndSendsResultBeforeContinuingPastRequiresAction(boolean isError) throws Exception {
        var runner = runner(Duration.ofSeconds(5));
        String tool = """
                {"id":"tool1","type":"agent.custom_tool_use","name":"entries_add","input":{"kind":"drink"}}
                """;
        var body = events(tool, tool,
                "{\"id\":\"waiting\",\"type\":\"session.status_idle\",\"stop_reason\":{\"type\":\"requires_action\"}}",
                "{\"type\":\"session.thread_status_idle\",\"id\":\"thread_end\",\"stop_reason\":{\"type\":\"end_turn\"}}",
                END);
        when(api.openStream("sesn_test")).thenReturn(new EventStream(body));
        String resultJson = isError ? "{\"ok\":false}" : "{\"ok\":true}";
        var expectedContext = new ToolContext(session.sessionRowId(), null, ZONE, LocalDate.of(2026, 10, 6), NOW);
        JsonNode input = JSON.readTree("{\"kind\":\"drink\"}");
        when(dispatcher.dispatch("tool1", "entries_add", input, expectedContext))
                .thenReturn(new DispatchOutcome(resultJson, isError));

        assertThat(runner.run("coffee", ZONE)).isEqualTo(new TurnResult("", 1));

        var order = inOrder(api, dispatcher);
        order.verify(api).openStream("sesn_test");
        order.verify(api).sendEvents(eq("sesn_test"), anyList());
        order.verify(dispatcher).dispatch("tool1", "entries_add", input, expectedContext);
        order.verify(api).sendEvents("sesn_test", List.of(Map.of(
                "type", "user.custom_tool_result", "custom_tool_use_id", "tool1", "is_error", isError,
                "content", List.of(Map.of("type", "text", "text", resultJson)))));
        verifyNoMoreInteractions(api, dispatcher);
        verify(body).close();
    }

    @Test
    void consumesTheRecordedCoffeeSession() throws Exception {
        var runner = runner(Duration.ofSeconds(5));
        JsonNode recording;
        try (var resource = getClass().getResourceAsStream("/streams/had-a-coffee.json")) {
            recording = JSON.readTree(resource);
        }
        var frames = new java.util.ArrayList<String>();
        var expectedReply = new StringBuilder();
        for (JsonNode event : recording) {
            frames.add(JSON.writeValueAsString(event));
            if ("agent.message".equals(event.path("type").asString())) {
                for (var block : event.path("content")) {
                    if ("text".equals(block.path("type").asString())) expectedReply.append(block.path("text").asString());
                }
            }
        }
        var body = events(frames.toArray(String[]::new));
        when(api.openStream("sesn_test")).thenReturn(new EventStream(body));
        when(dispatcher.dispatch(anyString(), eq("entries_add"), any(), any()))
                .thenReturn(new DispatchOutcome("{\"ok\":true}", false));

        assertThat(runner.run("had a coffee", ZONE)).isEqualTo(new TurnResult(expectedReply.toString(), 1));
        assertThat(expectedReply).isNotEmpty();
        verify(dispatcher).dispatch(anyString(), eq("entries_add"), any(), any());
        verify(body).close();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"type\":\"session.error\",\"id\":\"error\",\"error\":{\"message\":\"private content\"}}",
            "{\"type\":\"session.status_idle\",\"id\":\"idle\",\"stop_reason\":{\"type\":\"budget_reached\"}}",
            "{\"type\":\"agent.custom_tool_use\",\"id\":\"tool\",\"name\":\"entries_add\"}"
    })
    void errorsFailAndCloseWithoutExposingEventContents(String event) throws Exception {
        var runner = runner(Duration.ofSeconds(5));
        var body = events(event);
        when(api.openStream("sesn_test")).thenReturn(new EventStream(body));

        assertThatThrownBy(() -> runner.run("hello", ZONE)).isInstanceOf(IOException.class)
                .hasMessageNotContaining("private content");
        verify(body).close();
    }

    @Test
    void droppedStreamFailsWithoutReturningPartialReplyOrReconnecting() throws Exception {
        var runner = runner(Duration.ofSeconds(5));
        var body = events("{\"type\":\"agent.message\",\"id\":\"partial\",\"content\":[{\"type\":\"text\",\"text\":\"partial\"}]}");
        when(api.openStream("sesn_test")).thenReturn(new EventStream(body));
        assertThatThrownBy(() -> runner.run("hello", ZONE)).isInstanceOf(IOException.class)
                .hasMessageContaining("before end_turn");
        verify(api).openStream("sesn_test");
        verify(body).close();
    }

    @Test
    void sendFailureClosesStreamAndIsNotRetried() throws Exception {
        var runner = runner(Duration.ofSeconds(5));
        var body = events(END);
        when(api.openStream("sesn_test")).thenReturn(new EventStream(body));
        doThrow(new IOException("send failed")).when(api).sendEvents(anyString(), anyList());
        assertThatThrownBy(() -> runner.run("hello", ZONE)).isInstanceOf(IOException.class)
                .hasMessage("send failed");
        verify(api).sendEvents(anyString(), anyList());
        verify(body).close();
    }

    @Test
    void dispatcherFailureAlsoClosesStream() throws Exception {
        var runner = runner(Duration.ofSeconds(5));
        var body = events("{\"type\":\"agent.custom_tool_use\",\"id\":\"tool\",\"name\":\"entries_add\",\"input\":{}}");
        when(api.openStream("sesn_test")).thenReturn(new EventStream(body));
        when(dispatcher.dispatch(anyString(), anyString(), any(), any())).thenThrow(new IllegalStateException("db unavailable"));
        assertThatThrownBy(() -> runner.run("hello", ZONE)).isInstanceOf(IllegalStateException.class);
        verify(body).close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void timeoutClosesBlockedReadAndSendsOneInterruptEvenIfInterruptFails(boolean interruptFails) throws Exception {
        var runner = runner(Duration.ofMillis(500));
        var body = new BlockingInput();
        when(api.openStream("sesn_test")).thenReturn(new EventStream(body));
        var interrupt = List.of(Map.of("type", "user.interrupt"));
        if (interruptFails) doThrow(new IOException("interrupt failed")).when(api).sendEvents("sesn_test", interrupt);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertThatThrownBy(() -> runner.run("hello", ZONE)).isInstanceOf(HttpTimeoutException.class)
                    .satisfies(error -> assertThat(error.getSuppressed()).hasSize(interruptFails ? 1 : 0));
            assertThat(body.readStarted.getCount()).isZero();
            assertThat(body.closed.getCount()).isZero();
            verify(api).sendEvents("sesn_test", interrupt);
            verify(api, times(2)).sendEvents(eq("sesn_test"), anyList());
        });
    }

    @Test
    void timeoutAlsoCoversOpeningTheStream() throws Exception {
        var runner = runner(Duration.ofMillis(500));
        var stopped = new CountDownLatch(1);
        when(api.openStream("sesn_test")).thenAnswer(invocation -> {
            try {
                new CountDownLatch(1).await();
                throw new AssertionError("Unreachable");
            } finally {
                stopped.countDown();
            }
        });
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertThatThrownBy(() -> runner.run("hello", ZONE)).isInstanceOf(HttpTimeoutException.class);
            assertThat(stopped.await(1, TimeUnit.SECONDS)).isTrue();
            verify(api).sendEvents("sesn_test", List.of(Map.of("type", "user.interrupt")));
            verify(api).sendEvents(anyString(), anyList());
        });
    }

    private static InputStream events(String... events) {
        var sse = new StringBuilder();
        for (String event : events) {
            // Compact multiline fixtures so each SSE data frame contains one JSON value.
            sse.append("data: ").append(JSON.writeValueAsString(JSON.readTree(event))).append("\n\n");
        }
        return spy(new ByteArrayInputStream(sse.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static final class BlockingInput extends InputStream {
        final CountDownLatch readStarted = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public int read() throws IOException {
            readStarted.countDown();
            // Simulate a stream that needs close(), not just a thread interrupt, to unblock.
            while (closed.getCount() != 0) {
                try {
                    closed.await();
                } catch (InterruptedException ignored) { }
            }
            throw new IOException("closed");
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }
}
