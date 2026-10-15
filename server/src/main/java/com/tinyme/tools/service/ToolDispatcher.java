package com.tinyme.tools.service;

import com.tinyme.tools.model.DispatchOutcome;
import com.tinyme.tools.model.RegisteredTool;
import com.tinyme.tools.model.StoredCall;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolResult;
import com.tinyme.tools.repository.ToolCallRepository;
import com.tinyme.agent.config.AgentToolAllowLists;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.core.type.TypeReference;

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
    private final AgentToolAllowLists allowLists;

    @Autowired
    public ToolDispatcher(ToolRegistry registry, ToolValidator validator, ToolCallRepository calls,
                          AgentToolAllowLists allowLists) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.calls = Objects.requireNonNull(calls, "calls");
        this.allowLists = Objects.requireNonNull(allowLists, "allowLists");
    }

    public ToolDispatcher(ToolRegistry registry, ToolValidator validator, ToolCallRepository calls) {
        this(registry, validator, calls, AgentToolAllowLists.forTests());
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
            toolCallId = calls.insertRunning(base.sessionRowId(), eventId, toolName, storedInput(toolName, input),
                    base.agent(), base.threadId());
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
        if (!allowLists.allows(base.agent(), toolName)) {
            return finishError(toolCallId, toolName, eventId, "tool_not_allowed",
                    "Tool is not available to this agent");
        }
        if (!registered.implemented()) {
            return finishError(toolCallId, toolName, eventId, "not_implemented",
                    "Tool '" + toolName + "' is not implemented yet");
        }

        List<String> errors = validator.validate(toolName, input);
        if (!errors.isEmpty()) {
            return finishError(toolCallId, toolName, eventId, "validation_error",
                    errors.stream().limit(5).collect(Collectors.joining("; ")));
        }

        final ToolResult result;
        try {
            result = Objects.requireNonNull(registered.toolHandler().handle(input, context),
                    "Tool handler returned null");
        } catch (Exception exception) {
            log.error("Tool execution failed tool={} eventId={} code=internal_error",
                    toolName, eventId, exception);
            return finish(toolCallId, toolName, eventId,
                    new ToolResult.Err("internal_error", "Tool execution failed"));
        }

        return finish(toolCallId, toolName, eventId, result);
    }

    /** Draft bodies are needed for the live Gmail request, but never belong in the permanent tool audit row. */
    private static JsonNode storedInput(String toolName, JsonNode input) {
        if (!"gmail_draft".equals(toolName) || !input.isObject()) return input;
        ObjectNode redacted = ((ObjectNode) input).deepCopy();
        redacted.remove("body");
        return redacted;
    }

    private DispatchOutcome replayOrAlreadyRunning(StoredCall stored, String toolName, String eventId) {
        if ("done".equals(stored.status()) || "failed".equals(stored.status())) {
            if (stored.result() != null) {
                if (stored.isError()) {
                    logError(toolName, eventId, storedErrorCode(stored));
                }
                return new DispatchOutcome(modelJson(stored.result()), stored.isError(),
                        storedRef(stored.result()));
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
        Map<String, Object> ref = isError && List.of("places_near", "places_search").contains(toolName)
                && "no_location".equals(code)
                ? Map.of("error_code", "no_location") : Map.of();
        if (!isError && result instanceof ToolResult.Ok success) ref = success.ref();
        String resultJson;
        String storedResultJson;
        try {
            resultJson = envelopeJson(result);
            storedResultJson = result instanceof ToolResult.Ok success && success.stored() != null
                    ? successEnvelopeJson(success) : resultJson;
        } catch (RuntimeException serializationFailure) {
            isError = true;
            code = "internal_error";
            ref = Map.of();
            result = new ToolResult.Err(code, "Tool result could not be serialized");
            resultJson = errorJson(code, ((ToolResult.Err) result).message());
            storedResultJson = resultJson;
        }

        String status = isError && "internal_error".equals(code) ? "failed" : "done";
        try {
            // The stored row keeps ref for replays; the model only ever gets resultJson.
            ObjectNode stored = (ObjectNode) JSON.readTree(storedResultJson);
            if (!ref.isEmpty()) stored.set("ref", JSON.valueToTree(ref));
            calls.finish(id, stored, isError, status);
        } catch (RuntimeException persistenceFailure) {
            logError(toolName, eventId, "internal_error");
            throw persistenceFailure;
        }
        if (isError) {
            logError(toolName, eventId, code);
        }
        return new DispatchOutcome(resultJson, isError, ref);
    }

    /**
     * What the model sees: ok, data and summary, or the error. Never ref: it is for the phone (chips, place
     * cards) and may hold coordinates. Everything the model needs, ids included, is in data.
     */
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

    private static String successEnvelopeJson(ToolResult.Ok success) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("ok", true);
        envelope.put("data", success.stored());
        envelope.put("summary", success.summary());
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

    /** A stored result without its ref, as the model first got it. */
    private static String modelJson(JsonNode stored) {
        if (!stored.isObject() || stored.get("ref") == null) return JSON.writeValueAsString(stored);
        ObjectNode copy = ((ObjectNode) stored).deepCopy();
        copy.remove("ref");
        return JSON.writeValueAsString(copy);
    }

    private static Map<String, Object> storedRef(JsonNode result) {
        JsonNode ref = result.get("ref");
        return ref == null || !ref.isObject() ? Map.of()
                : JSON.convertValue(ref, new TypeReference<Map<String, Object>>() {});
    }

    private static void logError(String toolName, String eventId, String code) {
        log.warn("Tool dispatch error tool={} eventId={} code={}", toolName, eventId, code);
    }
}
