package com.tinyme.tools.service;

import com.tinyme.tools.model.DispatchOutcome;
import com.tinyme.tools.model.RegisteredTool;
import com.tinyme.tools.model.StoredCall;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolResult;
import com.tinyme.tools.repository.ToolCallRepository;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class ToolDispatcher {
    private static final Logger log = LoggerFactory.getLogger(ToolDispatcher.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ToolRegistry registry;
    private final ToolValidator validator;
    private final ToolCallRepository calls;

    public ToolDispatcher(ToolRegistry registry, ToolValidator validator, ToolCallRepository calls) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.calls = Objects.requireNonNull(calls, "calls");
    }

    public DispatchOutcome dispatch(String eventId, String toolName,
                                   JsonNode input, ToolContext base) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(base, "base");

        Optional<StoredCall> previous = calls.findByEventId(eventId);
        if (previous.isPresent()) {
            return replayOrAlreadyRunning(previous.get(), toolName, eventId);
        }

        final java.util.UUID toolCallId;
        try {
            toolCallId = calls.insertRunning(base.sessionRowId(), eventId, toolName, input);
        } catch (DuplicateKeyException duplicate) {
            return calls.findByEventId(eventId)
                    .map(stored -> replayOrAlreadyRunning(stored, toolName, eventId))
                    .orElseGet(() -> errorWithoutRow(toolName, eventId,
                            "already_running", "This tool call is already running"));
        }

        ToolContext context = base.withToolCallId(toolCallId);
        Optional<RegisteredTool> registration = registry.find(toolName);
        if (registration.isEmpty()) {
            return finishError(toolCallId, toolName, eventId, "unknown_tool",
                    "Unknown tool '" + toolName + "'");
        }

        RegisteredTool registered = registration.get();
        if (!registered.implemented()) {
            return finishError(toolCallId, toolName, eventId, "not_implemented",
                    "Tool '" + toolName + "' is not implemented yet");
        }

        List<String> errors = validator.validate(toolName, input);
        if (!errors.isEmpty()) {
            return finishError(toolCallId, toolName, eventId, "invalid_input",
                    errors.stream().limit(5).collect(Collectors.joining("; ")));
        }

        final ToolResult result;
        try {
            result = Objects.requireNonNull(registered.toolHandler().handle(input, context),
                    "Tool handler returned null");
        } catch (Exception exception) {
            return finish(toolCallId, toolName, eventId,
                    new ToolResult.Err("internal_error", "Tool execution failed"));
        }

        return finish(toolCallId, toolName, eventId, result);
    }

    private DispatchOutcome replayOrAlreadyRunning(StoredCall stored, String toolName, String eventId) {
        if ("done".equals(stored.status()) || "failed".equals(stored.status())) {
            if (stored.result() != null) {
                if (stored.isError()) {
                    logError(toolName, eventId, storedErrorCode(stored));
                }
                return new DispatchOutcome(JSON.writeValueAsString(stored.result()), stored.isError());
            }
            logError(toolName, eventId, "internal_error");
            return new DispatchOutcome(errorJson("internal_error", "Stored tool result is missing"), true);
        }
        return errorWithoutRow(toolName, eventId, "already_running", "This tool call is already running");
    }

    private DispatchOutcome finishError(java.util.UUID id, String toolName, String eventId,
                                        String code, String message) {
        return finish(id, toolName, eventId, new ToolResult.Err(code, message));
    }

    private DispatchOutcome errorWithoutRow(String toolName, String eventId, String code, String message) {
        logError(toolName, eventId, code);
        return new DispatchOutcome(errorJson(code, message), true);
    }

    private DispatchOutcome finish(java.util.UUID id, String toolName, String eventId, ToolResult result) {
        boolean isError = result instanceof ToolResult.Err;
        String code = isError ? ((ToolResult.Err) result).code() : null;
        String resultJson;
        try {
            resultJson = envelopeJson(result);
        } catch (RuntimeException serializationFailure) {
            isError = true;
            code = "internal_error";
            result = new ToolResult.Err(code, "Tool result could not be serialized");
            resultJson = errorJson(code, ((ToolResult.Err) result).message());
        }

        String status = isError && "internal_error".equals(code) ? "failed" : "done";
        try {
            calls.finish(id, JSON.readTree(resultJson), isError, status);
        } catch (RuntimeException persistenceFailure) {
            logError(toolName, eventId, "internal_error");
            throw persistenceFailure;
        }
        if (isError) {
            logError(toolName, eventId, code);
        }
        return new DispatchOutcome(resultJson, isError);
    }

    private static String envelopeJson(ToolResult result) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        if (result instanceof ToolResult.Ok success) {
            envelope.put("ok", true);
            envelope.put("data", success.data());
            envelope.put("summary", success.summary());
        } else {
            ToolResult.Err error = (ToolResult.Err) result;
            envelope.put("ok", false);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("code", error.code());
            detail.put("message", error.message());
            envelope.put("error", detail);
        }
        return JSON.writeValueAsString(envelope);
    }

    private static String errorJson(String code, String message) {
        return envelopeJson(new ToolResult.Err(code, message));
    }

    private static String storedErrorCode(StoredCall stored) {
        var error = stored.result().get("error");
        var code = error == null ? null : error.get("code");
        return code != null && code.isString() ? code.stringValue() : "tool_error";
    }

    private static void logError(String toolName, String eventId, String code) {
        log.warn("Tool dispatch error tool={} eventId={} code={}", toolName, eventId, code);
    }
}
