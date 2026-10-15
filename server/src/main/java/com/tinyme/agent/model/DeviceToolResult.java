package com.tinyme.agent.model;

import tools.jackson.databind.JsonNode;

import java.util.Objects;

/** Normalized result returned by the phone for a server-requested device action. */
public record DeviceToolResult(boolean succeeded, JsonNode data, String errorCode, String errorMessage) {
    public static DeviceToolResult success(JsonNode data) {
        return new DeviceToolResult(true, Objects.requireNonNull(data, "data"), null, null);
    }

    public static DeviceToolResult failure(String code, String message) {
        return new DeviceToolResult(false, null,
                code == null || code.isBlank() ? "device_error" : code,
                message == null || message.isBlank() ? "The phone could not complete this action" : message);
    }

    public DeviceToolResult {
        if (succeeded) Objects.requireNonNull(data, "data");
        else {
            if (errorCode == null || errorCode.isBlank()) throw new IllegalArgumentException("errorCode is required");
            if (errorMessage == null || errorMessage.isBlank()) throw new IllegalArgumentException("errorMessage is required");
        }
    }
}
