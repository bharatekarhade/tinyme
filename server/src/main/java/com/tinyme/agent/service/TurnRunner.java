package com.tinyme.agent.service;

import com.tinyme.agent.client.EventStream;
import com.tinyme.agent.client.ManagedAgentApi;
import com.tinyme.agent.model.SessionRef;
import com.tinyme.agent.model.TurnResult;
import com.tinyme.agent.model.MessageRole;
import com.tinyme.agent.repository.MessageRepository;
import com.tinyme.tools.model.DispatchOutcome;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.service.ToolDispatcher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ArrayList;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class TurnRunner {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final SessionManager sessions;
    private final ContextPrefixBuilder contextPrefix;
    private final ManagedAgentApi api;
    private final ToolDispatcher dispatcher;
    private final MessageRepository messages;
    private final Clock clock;
    private final Duration timeout;

    TurnRunner(SessionManager sessions, ContextPrefixBuilder contextPrefix, ManagedAgentApi api,
               ToolDispatcher dispatcher, MessageRepository messages, Clock clock,
               @Value("${tinyme.agent.turn-timeout:120s}") Duration timeout) {
        this.sessions = sessions;
        this.contextPrefix = contextPrefix;
        this.api = api;
        this.dispatcher = dispatcher;
        this.messages = messages;
        this.clock = clock;
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Turn timeout must be positive");
        }
        this.timeout = timeout;
    }

    public TurnResult run(String text, ZoneId zone) throws IOException, InterruptedException {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(zone, "zone");
        if (text.isBlank()) throw new IllegalArgumentException("Message text must not be blank");

        // A separate worker lets the deadline cancel even a blocked HTTP call or SSE read.
        var state = new TurnState();
        var task = new FutureTask<>(() -> execute(text, zone, state));
        Thread.ofVirtual().name("tinyme-turn").start(task);
        try {
            return task.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException expired) {
            var failure = new HttpTimeoutException("Managed Agents turn timed out after " + timeout);
            cancel(task, state, failure);
            if (state.session != null) {
                try {
                    api.sendEvents(state.session.anthropicSessionId(), List.of(Map.of("type", "user.interrupt")));
                } catch (IOException | RuntimeException interruptFailure) {
                    failure.addSuppressed(interruptFailure);
                } catch (InterruptedException interruptFailure) {
                    Thread.currentThread().interrupt();
                    failure.addSuppressed(interruptFailure);
                }
            }
            throw failure;
        } catch (InterruptedException interrupted) {
            cancel(task, state, interrupted);
            throw interrupted;
        } catch (ExecutionException failed) {
            switch (failed.getCause()) {
                case IOException error -> throw error;
                case InterruptedException error -> throw error;
                case RuntimeException error -> throw error;
                case Error error -> throw error;
                default -> throw new IOException("Managed Agents turn failed", failed.getCause());
            }
        }
    }

    private TurnResult execute(String text, ZoneId zone, TurnState state) throws IOException, InterruptedException {
        var session = sessions.todaySession(zone);
        state.session = session;
        state.checkCancelled();
        var now = clock.instant();
        var context = new ToolContext(session.sessionRowId(), null, zone, LocalDate.ofInstant(now, zone), now);
        String prefix = contextPrefix.build(zone);
        state.checkCancelled();
        try (var stream = api.openStream(session.anthropicSessionId())) {
            state.stream = stream;
            state.checkCancelled();
            messages.insert(session.sessionRowId(), MessageRole.USER, text);
            api.sendEvents(session.anthropicSessionId(), List.of(Map.of(
                    "type", "user.message", "content", List.of(textBlock(prefix), textBlock(text)))));

            var seen = new HashSet<String>();
            var reply = new StringBuilder();
            var actions = new ArrayList<Map<String, Object>>();
            int toolCalls = 0;
            for (;;) {
                state.checkCancelled();
                JsonNode event = stream.next();
                state.checkCancelled();
                if (event == null) throw new IOException("Managed Agents stream ended before end_turn");
                String type = requiredText(event, "type");
                // Ignore built-in tool activity, usage, thread status and any preview events.
                if (!List.of("agent.custom_tool_use", "agent.message", "session.status_idle", "session.error")
                        .contains(type)) continue;
                String id = requiredText(event, "id");
                if (!seen.add(id)) continue;

                switch (type) {
                    case "agent.custom_tool_use" -> {
                        String name = requiredText(event, "name");
                        JsonNode input = event.get("input");
                        if (input == null) throw new IOException("Custom tool event is missing input");
                        // EventStream already supplies Jackson 3 nodes, matching the dispatcher.
                        var result = dispatcher.dispatch(id, name, input, context);
                        toolCalls++;
                        actions.add(Map.of("tool", name, "summary", actionSummary(result),
                                "isError", result.isError()));
                        state.checkCancelled();
                        api.sendEvents(session.anthropicSessionId(), List.of(Map.of(
                                "type", "user.custom_tool_result", "custom_tool_use_id", id,
                                "content", List.of(textBlock(result.resultJson())), "is_error", result.isError())));
                    }
                    case "agent.message" -> appendText(reply, event);
                    case "session.status_idle" -> {
                        String reason = requiredText(event.path("stop_reason"), "type");
                        if (reason.equals("end_turn")) {
                            if (!reply.isEmpty() || !actions.isEmpty()) {
                                messages.insert(session.sessionRowId(), MessageRole.ASSISTANT,
                                        reply.toString(), actions);
                            }
                            return new TurnResult(reply.toString(), toolCalls);
                        }
                        if (!reason.equals("requires_action")) {
                            throw new IOException("Managed Agents session stopped without end_turn");
                        }
                    }
                    case "session.error" -> throw new IOException("Managed Agents session reported an error");
                    default -> { }
                }
            }
        }
    }

    private static void appendText(StringBuilder reply, JsonNode event) throws IOException {
        JsonNode content = event.get("content");
        if (content == null || !content.isArray()) throw new IOException("Agent message is missing content");
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asString())) {
                JsonNode text = block.get("text");
                if (text == null || !text.isString()) throw new IOException("Agent text block is missing text");
                reply.append(text.stringValue());
            }
        }
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
        EventStream stream = state.stream;
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }

    private static final class TurnState {
        volatile SessionRef session;
        volatile EventStream stream;
        volatile boolean cancelled;

        void checkCancelled() throws InterruptedException {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Managed Agents turn cancelled");
            }
        }
    }
}
