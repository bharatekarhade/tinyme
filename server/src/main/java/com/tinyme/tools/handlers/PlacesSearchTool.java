package com.tinyme.tools.handlers;

import com.tinyme.agent.model.DeviceToolResult;
import com.tinyme.agent.service.DeviceToolGateway;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolHandler;
import com.tinyme.tools.model.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class PlacesSearchTool implements ToolHandler {
    private static final Duration DEVICE_TIMEOUT = Duration.ofSeconds(20);
    private final DeviceToolGateway gateway;

    public PlacesSearchTool(DeviceToolGateway gateway) { this.gateway = gateway; }
    @Override public String name() { return "places_search"; }

    @Override
    public ToolResult handle(JsonNode input, ToolContext context) {
        if (input == null || !input.isObject()) return invalid("Input must be an object");
        try {
            String query = ToolInputs.requiredString(input, "query").strip();
            if (query.length() > 120) throw new IllegalArgumentException("query must be at most 120 characters");
            String near = ToolInputs.optionalString(input, "near");
            if (near != null) {
                near = near.strip();
                if (near.isEmpty() || near.length() > 120) {
                    throw new IllegalArgumentException("near must be between 1 and 120 characters");
                }
            } else if (context.location() != null) {
                near = "here";
            }

            Map<String, Object> request = new LinkedHashMap<>();
            request.put("query", query);
            if (near != null) request.put("near", near);
            DeviceToolGateway.CompletedDeviceAction completed = gateway.callWithActionId(context, name(), request,
                    DEVICE_TIMEOUT);
            DeviceToolResult result = completed.result();
            if (!result.succeeded()) {
                return new ToolResult.Err(result.errorCode(), result.errorMessage());
            }
            JsonNode data = result.data();
            if (!data.isArray()) return invalid("The phone returned an invalid place search result");
            List<Map<String, Object>> found = new ArrayList<>();
            for (int i = 0; i < Math.min(5, data.size()); i++) {
                JsonNode item = data.get(i);
                if (!item.isObject() || item.get("name") == null || !item.get("name").isString()) continue;
                String address = stringOrNull(item.get("address"));
                String category = stringOrNull(item.get("category"));
                Double distance = numberOrNull(item.get("distance_m"));
                found.add(PlaceToolOutputs.candidate(completed.actionId() + ":" + i,
                        item.get("name").stringValue(), address, category, distance));
            }
            return new ToolResult.Ok(Map.of("candidates", found), found.isEmpty()
                    ? "No matching places found" : "Found " + found.size() + (found.size() == 1 ? " place" : " places"));
        } catch (DeviceToolGateway.DeviceUnavailableException unavailable) {
            return new ToolResult.Err("device_unavailable", "Place search needs TinyMe open on your iPhone");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new ToolResult.Err("device_unavailable", "Place search was interrupted");
        } catch (IllegalArgumentException invalid) {
            return invalid(invalid.getMessage());
        }
    }

    private static String stringOrNull(JsonNode value) {
        return value == null || value.isNull() || !value.isString() ? null : value.stringValue();
    }
    private static Double numberOrNull(JsonNode value) {
        return value == null || value.isNull() || !value.isNumber() ? null : value.doubleValue();
    }
    private static ToolResult.Err invalid(String message) {
        return new ToolResult.Err("validation_error", message == null ? "Invalid place search" : message);
    }
}
