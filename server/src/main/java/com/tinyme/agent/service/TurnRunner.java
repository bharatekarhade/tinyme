package com.tinyme.agent.service;

import com.tinyme.agent.client.ManagedAgents;
import com.tinyme.agent.client.ManagedAgentApi;
import com.tinyme.agent.model.MessageRole;
import com.tinyme.agent.model.SessionRef;
import com.tinyme.agent.model.TurnEvent;
import com.tinyme.agent.model.TurnRequest;
import com.tinyme.agent.model.TurnResult;
import com.tinyme.agent.repository.MessageRepository;
import com.tinyme.tools.model.DispatchOutcome;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.service.ToolDispatcher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

@Service
public class TurnRunner {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ContextPrefixBuilder contextPrefix;
    private final ManagedAgents api;
    private final ToolDispatcher dispatcher;
    private final SessionTurnLock sessionTurnLock;
    private final MessageRepository messages;
    private final Clock clock;
    private final Duration timeout;
    private final ResilientEventSource.Sleeper reconnectSleeper;

    @Autowired
    TurnRunner(ContextPrefixBuilder contextPrefix, ManagedAgents api,
               ToolDispatcher dispatcher, SessionTurnLock sessionTurnLock, MessageRepository messages, Clock clock,
               @Value("${tinyme.agent.turn-timeout:120s}") Duration timeout) {
        this(contextPrefix, api, dispatcher, sessionTurnLock, messages, clock, timeout, Thread::sleep);
    }

    TurnRunner(ContextPrefixBuilder contextPrefix, ManagedAgents api,
               ToolDispatcher dispatcher, SessionTurnLock sessionTurnLock, MessageRepository messages, Clock clock,
               Duration timeout, ResilientEventSource.Sleeper reconnectSleeper) {
        this.contextPrefix = contextPrefix;
        this.api = api;
        this.dispatcher = dispatcher;
        this.sessionTurnLock = sessionTurnLock;
        this.messages = messages;
        this.clock = clock;
        this.reconnectSleeper = Objects.requireNonNull(reconnectSleeper, "reconnectSleeper");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Turn timeout must be positive");
        }
        this.timeout = timeout;
    }

    public TurnResult run(TurnRequest request, TurnListener listener) throws IOException, InterruptedException {
        return run(request, listener, ignored -> { });
    }

    public TurnResult run(TurnRequest request, TurnListener listener, Consumer<JsonNode> rawEventObserver)
            throws IOException, InterruptedException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(rawEventObserver, "rawEventObserver");
        if (request.text().isBlank()) throw new IllegalArgumentException("Message text must not be blank");

        // A separate worker lets the deadline cancel even a blocked HTTP call or SSE read.
        var state = new TurnState();
        state.session = request.session();
        var task = new FutureTask<>(() -> execute(request, listener, rawEventObserver, state));
        Thread.ofVirtual().name("tinyme-turn").start(task);
        try {
            state.lockAcquiredSignal.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            return task.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException expired) {
            if (!state.lockAcquired) {
                var busy = new IOException("Another message is still being answered");
                cancel(task, state, busy);
                listener.on(new TurnEvent.Failed("busy", busy.getMessage(), true));
                throw busy;
            }
            var failure = new HttpTimeoutException("Managed Agents turn timed out after " + timeout);
            cancel(task, state, failure);
            if (state.session != null && state.lockAcquired) {
                try {
                    api.sendEvents(state.session.anthropicSessionId(), List.of(Map.of("type", "user.interrupt")));
                } catch (IOException | RuntimeException interruptFailure) {
                    failure.addSuppressed(interruptFailure);
                } catch (InterruptedException interruptFailure) {
                    Thread.currentThread().interrupt();
                    failure.addSuppressed(interruptFailure);
                }
            }
            listener.on(new TurnEvent.Failed("turn_timeout", failure.getMessage(), true));
            throw failure;
        } catch (InterruptedException interrupted) {
            cancel(task, state, interrupted);
            listener.on(new TurnEvent.Failed("interrupted", "Turn was interrupted", false));
            throw interrupted;
        } catch (ExecutionException failed) {
            notifyFailure(listener, failed.getCause());
            switch (failed.getCause()) {
                case IOException error -> throw error;
                case InterruptedException error -> throw error;
                case RuntimeException error -> throw error;
                case Error error -> throw error;
                default -> throw new IOException("Managed Agents turn failed", failed.getCause());
            }
        }
    }

    private TurnResult execute(TurnRequest request, TurnListener listener, Consumer<JsonNode> rawEventObserver,
                               TurnState state)
            throws IOException, InterruptedException {
        UUID sessionRowId = request.session().sessionRowId();
        boolean acquired = sessionTurnLock.acquire(sessionRowId, timeout);
        state.lockAcquired = acquired;
        state.lockAcquiredSignal.complete(acquired);
        if (!acquired) {
            throw new TurnFailure("busy", "Another message is still being answered", true);
        }
        try {
            return executeLocked(request, listener, rawEventObserver, state);
        } finally {
            sessionTurnLock.release(sessionRowId);
        }
    }

    private TurnResult executeLocked(TurnRequest request, TurnListener listener, Consumer<JsonNode> rawEventObserver,
                                     TurnState state)
            throws IOException, InterruptedException {
        var session = request.session();
        var text = request.text();
        var zone = request.zone();
        state.checkCancelled();
        var now = clock.instant();
        var context = new ToolContext(session.sessionRowId(), null, zone, LocalDate.ofInstant(now, zone), now);
        String prefix = contextPrefix.build(zone);
        state.checkCancelled();
        try (var source = new ResilientEventSource(api, session.anthropicSessionId(), reconnectSleeper, rawEventObserver)) {
            state.source = source;
            source.beginTurn(now);
            state.checkCancelled();
            api.sendEvents(session.anthropicSessionId(), List.of(Map.of(
                    "type", "user.message",
                    "content", List.of(textBlock(prefix), textBlock(text)))));

            var reply = new StringBuilder();
            var actions = new ArrayList<Map<String, Object>>();
            int toolCalls = 0;
            for (;;) {
                state.checkCancelled();
                JsonNode event = source.next();
                state.checkCancelled();
                if (event == null) throw new IOException("Managed Agents stream ended before end_turn");
                String type = requiredText(event, "type");
                // Ignore built-in tool activity, usage, thread status and any preview events.
                if (!List.of("agent.custom_tool_use", "agent.message", "session.status_idle", "session.error")
                        .contains(type)) continue;
                String id = requiredText(event, "id");
                switch (type) {
                    case "agent.custom_tool_use" -> {
                        String name = requiredText(event, "name");
                        JsonNode input = event.get("input");
                        if (input == null) throw new IOException("Custom tool event is missing input");
                        // EventStream already supplies Jackson 3 nodes, matching the dispatcher.
                        var result = dispatcher.dispatch(id, name, input, context);
                        toolCalls++;
                        String summary = actionSummary(result);
                        actions.add(Map.of("tool", name, "summary", summary,
                                "isError", result.isError()));
                        listener.on(new TurnEvent.ActionDone(name, summary, result.isError()));
                        state.checkCancelled();
                        api.sendEvents(session.anthropicSessionId(), List.of(Map.of(
                                "type", "user.custom_tool_result", "custom_tool_use_id", id,
                                "content", List.of(textBlock(result.resultJson())), "is_error", result.isError())));
                    }
                    case "agent.message" -> {
                        String textEvent = messageText(event);
                        reply.append(textEvent);
                        if (!textEvent.isEmpty()) listener.on(new TurnEvent.Text(textEvent));
                    }
                    case "session.status_idle" -> {
                        String reason = requiredText(event.path("stop_reason"), "type");
                        if (reason.equals("end_turn")) {
                            if (!reply.isEmpty() || !actions.isEmpty()) {
                                messages.insert(session.sessionRowId(), MessageRole.ASSISTANT,
                                        reply.toString(), actions);
                            }
                            return new TurnResult(reply.toString(), toolCalls);
                        }
                        if (reason.equals("requires_action")) continue;
                        if (reason.equals("budget_reached")) {
                            if (!reply.isEmpty() || !actions.isEmpty()) {
                                messages.insert(session.sessionRowId(), MessageRole.ASSISTANT,
                                        reply.toString(), actions);
                            }
                            listener.on(new TurnEvent.Failed(
                                    "budget_reached", "Today's session hit its budget", false));
                            return new TurnResult(reply.toString(), toolCalls);
                        }
                        throw new TurnFailure("unexpected_stop", reason, true);
                    }
                    case "session.error" -> throw new TurnFailure(
                            "agent_error", "Managed Agents session reported an error", true);
                    default -> { }
                }
            }
        }
    }

    private static String messageText(JsonNode event) throws IOException {
        JsonNode content = event.get("content");
        if (content == null || !content.isArray()) throw new IOException("Agent message is missing content");
        var text = new StringBuilder();
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asString())) {
                JsonNode value = block.get("text");
                if (value == null || !value.isString()) throw new IOException("Agent text block is missing text");
                text.append(value.stringValue());
            }
        }
        return text.toString();
    }

    private static Map<String, String> textBlock(String text) {
        return Map.of("type", "text", "text", text);
    }

    private static String actionSummary(DispatchOutcome result) {
        try {
            JsonNode envelope = JSON.readTree(result.resultJson());
            JsonNode summary = result.isError() ? envelope.path("error").path("message") : envelope.path("summary");
            return summary.isString() ? summary.stringValue() : "Tool completed";
        } catch (RuntimeException malformedResult) {
            return "Tool completed";
        }
    }

    private static void notifyFailure(TurnListener listener, Throwable failure) {
        TurnEvent.Failed event = switch (failure) {
            case TurnFailure turnFailure -> new TurnEvent.Failed(
                    turnFailure.code, turnFailure.getMessage(), turnFailure.retryable);
            case ResilientEventSource.StreamLostException lost -> new TurnEvent.Failed(
                    "stream_lost", "The agent event stream could not be recovered", true);
            case ManagedAgentApi.ApiException apiError -> new TurnEvent.Failed(
                    apiError.type(), apiError.getMessage(), apiError.status() == 429 || apiError.status() >= 500);
            case HttpTimeoutException timeout -> new TurnEvent.Failed("turn_timeout", timeout.getMessage(), true);
            case InterruptedException ignored -> new TurnEvent.Failed("interrupted", "Turn was interrupted", false);
            case IOException ignored -> new TurnEvent.Failed("turn_failed", "The agent turn failed", true);
            case RuntimeException ignored -> new TurnEvent.Failed("internal_error", "The agent turn failed internally", false);
            case Error ignored -> new TurnEvent.Failed("internal_error", "The agent turn failed internally", false);
            default -> new TurnEvent.Failed("turn_failed", "The agent turn failed", false);
        };
        listener.on(event);
    }

    private static final class TurnFailure extends IOException {
        private final String code;
        private final boolean retryable;

        private TurnFailure(String code, String message, boolean retryable) {
            super(message);
            this.code = code;
            this.retryable = retryable;
        }
    }

    private static String requiredText(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            throw new IOException("Managed Agents event is missing " + field);
        }
        return value.stringValue();
    }

    private static void cancel(FutureTask<?> task, TurnState state, Exception failure) {
        state.cancelled = true;
        task.cancel(true);
        ResilientEventSource source = state.source;
        if (source != null) {
            try {
                source.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }

    private static final class TurnState {
        volatile SessionRef session;
        volatile ResilientEventSource source;
        volatile boolean cancelled;
        volatile boolean lockAcquired;
        final CompletableFuture<Boolean> lockAcquiredSignal = new CompletableFuture<>();

        void checkCancelled() throws InterruptedException {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Managed Agents turn cancelled");
            }
        }
    }
}
