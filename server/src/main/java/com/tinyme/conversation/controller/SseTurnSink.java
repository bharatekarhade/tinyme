package com.tinyme.conversation.controller;

import com.tinyme.agent.model.TurnEvent;
import com.tinyme.agent.model.TurnResult;
import com.tinyme.agent.service.TurnListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Turns a single conversation's domain events into one-line SSE JSON records. */
public final class SseTurnSink implements TurnListener {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ScheduledExecutorService HEARTBEATS = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "tinyme-sse-heartbeats");
        thread.setDaemon(true);
        return thread;
    });
    private static final long PING_SECONDS = 15;

    private final SseEmitter emitter;
    private ScheduledFuture<?> heartbeat;
    private boolean closed;
    private boolean failed;
    private UUID partialAssistantMessageId;

    public SseTurnSink(SseEmitter emitter) {
        this.emitter = emitter;
    }

    public synchronized void start(UUID userMessageId) {
        if (closed) return;
        sendJson(Map.of("type", "turn_started", "user_message_id", userMessageId));
        if (!closed) {
            heartbeat = HEARTBEATS.scheduleAtFixedRate(this::ping, PING_SECONDS, PING_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Override
    public synchronized void on(TurnEvent event) {
        if (closed) return;
        switch (event) {
            case TurnEvent.ActionDone action -> sendJson(Map.of(
                    "type", "action_done", "tool", action.tool(), "summary", action.summary(),
                    "is_error", action.isError()));
            case TurnEvent.Text text -> sendJson(Map.of("type", "text", "text", text.text()));
            case TurnEvent.Failed failure -> {
                failed = true;
                partialAssistantMessageId = failure.assistantMessageId();
                sendJson(Map.of("type", "failed", "code", failure.code(), "message", failure.message(),
                        "retryable", failure.retryable()));
            }
        }
    }

    public synchronized void complete(TurnResult result, Throwable failure) {
        if (closed) return;
        if (failure != null && !failed) {
            sendJson(Map.of("type", "failed", "code", "turn_failed",
                    "message", "The conversation could not be completed", "retryable", true));
        }
        if (!closed) sendDone(result);
        close();
    }

    private void sendDone(TurnResult result) {
        var event = new LinkedHashMap<String, Object>();
        event.put("type", "done");
        if (result != null) {
            event.put("reply_text", result.replyText());
            event.put("tool_calls", result.toolCalls());
            if (result.assistantMessageId() != null) {
                event.put("assistant_message_id", result.assistantMessageId());
            } else if (partialAssistantMessageId != null) {
                event.put("assistant_message_id", partialAssistantMessageId);
            }
        } else if (partialAssistantMessageId != null) {
            event.put("assistant_message_id", partialAssistantMessageId);
        }
        sendJson(event);
    }

    private synchronized void ping() {
        if (closed) return;
        try {
            emitter.send(SseEmitter.event().comment(" ping"));
        } catch (IOException disconnected) {
            closeWithError(disconnected);
        }
    }

    private void sendJson(Map<String, ?> event) {
        try {
            // Passing a JSON string ensures the event is emitted as one data line, even for multiline text.
            emitter.send(SseEmitter.event().data(JSON.writeValueAsString(new LinkedHashMap<>(event))));
        } catch (IOException | RuntimeException failure) {
            closeWithError(failure);
        }
    }

    private void closeWithError(Throwable failure) {
        stopHeartbeat();
        closed = true;
        emitter.completeWithError(failure);
    }

    private void close() {
        stopHeartbeat();
        closed = true;
        emitter.complete();
    }

    private void stopHeartbeat() {
        if (heartbeat != null) heartbeat.cancel(false);
    }
}
