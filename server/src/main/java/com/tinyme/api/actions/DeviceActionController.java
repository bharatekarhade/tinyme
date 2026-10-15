package com.tinyme.api.actions;

import com.tinyme.agent.model.DeviceToolResult;
import com.tinyme.agent.service.DeviceToolGateway;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/actions")
public class DeviceActionController {
    private static final int MAX_RESULT_BYTES = 65_536;
    private final DeviceToolGateway gateway;

    public DeviceActionController(DeviceToolGateway gateway) {
        this.gateway = gateway;
    }

    @PostMapping("/{id}/result")
    public ResponseEntity<Void> result(@PathVariable UUID id, @RequestBody JsonNode body) {
        if (body == null || !body.isObject()) throw badRequest("Result must be a JSON object");
        if (body.toString().getBytes(StandardCharsets.UTF_8).length > MAX_RESULT_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Device action result is too large");
        }
        DeviceToolResult result = parse(body);
        try {
            gateway.submitResult(id, result);
            return ResponseEntity.noContent().build();
        } catch (DeviceToolGateway.DeviceActionNotFoundException missing) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Device action not found", missing);
        } catch (DeviceToolGateway.DeviceActionConflictException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, conflict.getMessage(), conflict);
        }
    }

    private static DeviceToolResult parse(JsonNode body) {
        JsonNode ok = body.get("ok");
        if (ok != null) {
            if (!ok.isBoolean()) throw badRequest("ok must be a boolean");
            if (ok.booleanValue()) {
                JsonNode data = body.get("data");
                if (data == null) throw badRequest("data is required for a successful action");
                return DeviceToolResult.success(data);
            }
            JsonNode error = body.get("error");
            if (error == null || !error.isObject()) throw badRequest("error is required for a failed action");
            return DeviceToolResult.failure(text(error.get("code"), "device_error"),
                    text(error.get("message"), "The phone could not complete this action"));
        }

        JsonNode status = body.get("status");
        if (status == null || !status.isString()) throw badRequest("provide ok or status");
        if ("ok".equals(status.stringValue())) {
            JsonNode result = body.get("result");
            if (result == null) throw badRequest("result is required for a successful action");
            return DeviceToolResult.success(result);
        }
        if ("failed".equals(status.stringValue())) {
            JsonNode error = body.get("error");
            if (error != null && error.isObject()) {
                return DeviceToolResult.failure(text(error.get("code"), "device_error"),
                        text(error.get("message"), "The phone could not complete this action"));
            }
            return DeviceToolResult.failure("device_error", text(error, "The phone could not complete this action"));
        }
        throw badRequest("status must be 'ok' or 'failed'");
    }

    private static String text(JsonNode value, String fallback) {
        return value != null && value.isString() && !value.stringValue().isBlank()
                ? value.stringValue().strip() : fallback;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
