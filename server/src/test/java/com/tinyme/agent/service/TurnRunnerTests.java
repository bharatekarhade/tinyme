package com.tinyme.agent.service;

import com.tinyme.agent.client.EventStream;
import com.tinyme.agent.client.ManagedAgents;
import com.tinyme.agent.client.FakeManagedAgents;
import com.tinyme.agent.model.SessionRef;
import com.tinyme.agent.model.TurnEvent;
import com.tinyme.agent.model.TurnRequest;
import com.tinyme.agent.model.TurnResult;
import com.tinyme.agent.model.MessageRole;
import com.tinyme.agent.repository.MessageRepository;
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
import java.util.ArrayList;
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
    private final ContextPrefixBuilder prefix = mock(ContextPrefixBuilder.class);
    private final ManagedAgents api = mock(ManagedAgents.class);
    private final ToolDispatcher dispatcher = mock(ToolDispatcher.class);
    private final SessionTurnLock sessionTurnLock = new SessionTurnLock();
    private final MessageRepository messages = mock(MessageRepository.class);
    private final SessionRef session = new SessionRef(UUID.randomUUID(), "sesn_test");
    private static final UUID USER_MESSAGE_ID = UUID.fromString("5d589013-d823-4d72-aa14-3888c191872c");

    private TurnRunner runner(Duration timeout) throws Exception {
        when(prefix.build(ZONE)).thenReturn("[context]\ntz: Asia/Tokyo\n[/context]");
        return new TurnRunner(prefix, api, dispatcher, sessionTurnLock, messages,
                Clock.fixed(NOW, ZoneOffset.UTC), timeout);
    }

    private TurnRunner runner(Duration timeout, ManagedAgents agents) throws Exception {
        when(prefix.build(ZONE)).thenReturn("[context]\ntz: Asia/Tokyo\n[/context]");
        return new TurnRunner(prefix, agents, dispatcher, sessionTurnLock, messages,
                Clock.fixed(NOW, ZoneOffset.UTC), timeout);
    }

    private TurnRequest request(String text) {
        return new TurnRequest(session, USER_MESSAGE_ID, text, ZONE);
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

        assertThat(runner.run(request("hello"), TurnListener.NONE)).isEqualTo(new TurnResult("Hello there. Welcome!", 0));

        var order = inOrder(prefix, api);
        order.verify(prefix).build(ZONE);
        order.verify(api).openStream("sesn_test");
        order.verify(api).sendEvents("sesn_test", List.of(Map.of("type", "user.message", "content", List.of(
                Map.of("type", "text", "text", "[context]\ntz: Asia/Tokyo\n[/context]"),
                Map.of("type", "text", "text", "hello")))));
        verify(messages).insert(session.sessionRowId(), MessageRole.ASSISTANT,
                "Hello there. Welcome!", List.of());
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
        String resultJson = isError
                ? "{\"ok\":false,\"error\":{\"message\":\"Tool had an error\"}}"
                : "{\"ok\":true,\"summary\":\"Logged coffee\"}";
        var expectedContext = new ToolContext(session.sessionRowId(), null, ZONE, LocalDate.of(2026, 10, 6), NOW);
        JsonNode input = JSON.readTree("{\"kind\":\"drink\"}");
        when(dispatcher.dispatch("tool1", "entries_add", input, expectedContext))
                .thenReturn(new DispatchOutcome(resultJson, isError));

        assertThat(runner.run(request("coffee"), TurnListener.NONE)).isEqualTo(new TurnResult("", 1));

        var order = inOrder(api, dispatcher, messages);
        order.verify(api).openStream("sesn_test");
        order.verify(api).sendEvents(eq("sesn_test"), anyList());
        order.verify(dispatcher).dispatch("tool1", "entries_add", input, expectedContext);
        order.verify(api).sendEvents("sesn_test", List.of(Map.of(
                "type", "user.custom_tool_result", "custom_tool_use_id", "tool1", "is_error", isError,
                "content", List.of(Map.of("type", "text", "text", resultJson)))));
        order.verify(messages).insert(session.sessionRowId(), MessageRole.ASSISTANT, "",
                List.of(Map.of("tool", "entries_add",
                        "summary", isError ? "Tool had an error" : "Logged coffee", "isError", isError)));
        verifyNoMoreInteractions(api, dispatcher);
        verify(body).close();
    }

    @Test
    void consumesTheRecordedCoffeeSession() throws Exception {
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
        var fake = new FakeManagedAgents(frames);
        var runner = runner(Duration.ofSeconds(5), fake);
        when(dispatcher.dispatch(anyString(), eq("entries_add"), any(), any()))
                .thenReturn(new DispatchOutcome("{\"ok\":true,\"summary\":\"Logged drink\"}", false));

        var observed = new ArrayList<TurnEvent>();
        assertThat(runner.run(request("had a coffee"), observed::add))
                .isEqualTo(new TurnResult(expectedReply.toString(), 1));
        assertThat(expectedReply).isNotEmpty();
        assertThat(observed).hasSize(2);
        assertThat(observed.get(0)).isInstanceOf(TurnEvent.ActionDone.class)
                .isEqualTo(new TurnEvent.ActionDone("entries_add", "Logged drink", false));
        assertThat(observed.get(1)).isEqualTo(new TurnEvent.Text(expectedReply.toString()));
        verify(dispatcher).dispatch(anyString(), eq("entries_add"), any(), any());
        verify(messages).insert(eq(session.sessionRowId()), eq(MessageRole.ASSISTANT),
                eq(expectedReply.toString()), anyList());
        assertThat(fake.sentEvents()).anySatisfy(batch -> assertThat(batch.getFirst())
                .doesNotContainKey("client_msg_id"));
    }

    @Test
    void serializesTwoTurnsOnTheSameSessionUntilFirstEndTurn() throws Exception {
        var firstMessageSent = new CountDownLatch(1);
        var continueFirstTurn = new CountDownLatch(1);
        var firstAssistantSaved = new CountDownLatch(1);
        String reply = "{\"type\":\"agent.message\",\"id\":\"reply\",\"content\":[{\"type\":\"text\",\"text\":\"done\"}]}";
        List<String> fixture = List.of(
                JSON.writeValueAsString(JSON.readTree(reply)),
                JSON.writeValueAsString(JSON.readTree(END)));
        var fake = new FakeManagedAgents(fixture, () -> {
            firstMessageSent.countDown();
            try {
                if (!continueFirstTurn.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("test gate timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }, openNumber -> {
            if (openNumber == 2) assertThat(firstAssistantSaved.getCount()).isZero();
        });
        doAnswer(call -> {
            firstAssistantSaved.countDown();
            return UUID.randomUUID();
        }).when(messages).insert(eq(session.sessionRowId()), eq(MessageRole.ASSISTANT), anyString(), anyList());
        var runner = runner(Duration.ofSeconds(5), fake);
        var first = new java.util.concurrent.FutureTask<>(() -> runner.run(request("first"), TurnListener.NONE));
        Thread.ofVirtual().start(first);
        assertThat(firstMessageSent.await(2, TimeUnit.SECONDS)).isTrue();

        var second = new java.util.concurrent.FutureTask<>(() -> runner.run(
                new TurnRequest(session, UUID.randomUUID(), "second", ZONE), TurnListener.NONE));
        Thread.ofVirtual().start(second);
        assertThat(fake.openCount()).isEqualTo(1);

        continueFirstTurn.countDown();
        assertThat(first.get(3, TimeUnit.SECONDS).replyText()).isEqualTo("done");
        assertThat(second.get(3, TimeUnit.SECONDS).replyText()).isEqualTo("done");
        assertThat(fake.openCount()).isEqualTo(2);
    }

    @Test
    void emitsBusyWhenSessionLockCannotBeAcquiredWithinTurnTimeout() throws Exception {
        assertThat(sessionTurnLock.acquire(session.sessionRowId(), Duration.ZERO)).isTrue();
        var observed = new ArrayList<TurnEvent>();
        try {
            var runner = runner(Duration.ofMillis(200));
            assertThatThrownBy(() -> runner.run(request("hello"), observed::add))
                    .isInstanceOf(IOException.class).hasMessage("Another message is still being answered");
            assertThat(observed).containsExactly(new TurnEvent.Failed(
                    "busy", "Another message is still being answered", true));
            verifyNoInteractions(api);
        } finally {
            sessionTurnLock.release(session.sessionRowId());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"type\":\"session.status_idle\",\"id\":\"idle\",\"stop_reason\":{\"type\":\"budget_reached\"}}",
            "{\"type\":\"agent.custom_tool_use\",\"id\":\"tool\",\"name\":\"entries_add\"}"
    })
    void errorsFailAndCloseWithoutExposingEventContents(String event) throws Exception {
        var runner = runner(Duration.ofSeconds(5));
        var body = events(event);
        when(api.openStream("sesn_test")).thenReturn(new EventStream(body));

        assertThatThrownBy(() -> runner.run(request("hello"), TurnListener.NONE)).isInstanceOf(IOException.class)
                .hasMessageNotContaining("private content");
        var observed = new ArrayList<TurnEvent>();
        var failureBody = events(event);
        when(api.openStream("sesn_test")).thenReturn(new EventStream(failureBody));
        assertThatThrownBy(() -> runner.run(request("hello"), observed::add)).isInstanceOf(IOException.class);
        assertThat(observed).hasSize(1).first().isInstanceOf(TurnEvent.Failed.class);
        verify(body).close();
        verify(failureBody).close();
    }

    @Test
    void sessionErrorEmitsRetryableAgentErrorWithoutText() throws Exception {
        var runner = runner(Duration.ofSeconds(5));
        var body = events(
                "{\"type\":\"session.error\",\"id\":\"error\",\"error\":{\"message\":\"private content\"}}");
        when(api.openStream("sesn_test")).thenReturn(new EventStream(body));
        var observed = new ArrayList<TurnEvent>();

        assertThatThrownBy(() -> runner.run(request("hello"), observed::add)).isInstanceOf(IOException.class)
                .hasMessageNotContaining("private content");

        assertThat(observed).containsExactly(new TurnEvent.Failed(
                "agent_error", "Managed Agents session reported an error", true));
        verify(body).close();
    }

    @Test
    void skipsTextEventsWithoutTextBlocks() throws Exception {
        var runner = runner(Duration.ofSeconds(5));
        var body = events(
                "{\"type\":\"agent.message\",\"id\":\"msg\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"hidden\"}]}",
                END);
        when(api.openStream("sesn_test")).thenReturn(new EventStream(body));
        var observed = new ArrayList<TurnEvent>();

        assertThat(runner.run(request("hello"), observed::add)).isEqualTo(new TurnResult("", 0));
        assertThat(observed).isEmpty();
    }

    @Test
    void droppedStreamFailsWithoutReturningPartialReplyOrReconnecting() throws Exception {
        var runner = runner(Duration.ofSeconds(5));
        var body = events("{\"type\":\"agent.message\",\"id\":\"partial\",\"content\":[{\"type\":\"text\",\"text\":\"partial\"}]}");
        when(api.openStream("sesn_test")).thenReturn(new EventStream(body));
        assertThatThrownBy(() -> runner.run(request("hello"), TurnListener.NONE)).isInstanceOf(IOException.class)
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
        assertThatThrownBy(() -> runner.run(request("hello"), TurnListener.NONE)).isInstanceOf(IOException.class)
                .hasMessage("send failed");
        var order = inOrder(api);
        order.verify(api).openStream("sesn_test");
        order.verify(api).sendEvents(anyString(), anyList());
        verify(api).sendEvents(anyString(), anyList());
        verify(body).close();
    }

    @Test
    void dispatcherFailureAlsoClosesStream() throws Exception {
        var runner = runner(Duration.ofSeconds(5));
        var body = events("{\"type\":\"agent.custom_tool_use\",\"id\":\"tool\",\"name\":\"entries_add\",\"input\":{}}");
        when(api.openStream("sesn_test")).thenReturn(new EventStream(body));
        when(dispatcher.dispatch(anyString(), anyString(), any(), any())).thenThrow(new IllegalStateException("db unavailable"));
        assertThatThrownBy(() -> runner.run(request("hello"), TurnListener.NONE)).isInstanceOf(IllegalStateException.class);
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
        var observed = new ArrayList<TurnEvent>();

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertThatThrownBy(() -> runner.run(request("hello"), observed::add)).isInstanceOf(HttpTimeoutException.class)
                    .satisfies(error -> assertThat(error.getSuppressed()).hasSize(interruptFails ? 1 : 0));
            assertThat(observed).containsExactly(new TurnEvent.Failed(
                    "turn_timeout", "Managed Agents turn timed out after PT0.5S", true));
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
            assertThatThrownBy(() -> runner.run(request("hello"), TurnListener.NONE)).isInstanceOf(HttpTimeoutException.class);
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
