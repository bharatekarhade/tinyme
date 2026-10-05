package com.tinyme.tools.service;

import com.tinyme.tools.model.RegisteredTool;
import com.tinyme.tools.model.ToolHandler;
import com.tinyme.tools.model.ToolSpec;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class ToolRegistry {

    private final Map<String, RegisteredTool> toolsByName;

    public ToolRegistry(
            ToolSpecLoader toolSpecLoader,
            List<ToolHandler> toolHandlerList,
            @Value("${tinyme.tools.allow-missing-handlers:false}") boolean allowMissingHandlers) {
        Map<String, ToolHandler> handlersByName = new LinkedHashMap<>();
        for (ToolHandler handler : toolHandlerList) {
            ToolHandler previous = handlersByName.putIfAbsent(handler.name(), handler);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate tool handler name '%s'".formatted(handler.name()));
            }
        }

        Map<String, RegisteredTool> registrations = new LinkedHashMap<>();
        for (Map.Entry<String, ToolSpec> entry : toolSpecLoader.specs().entrySet()) {
            String name = entry.getKey();
            ToolHandler handler = handlersByName.remove(name);
            if (handler == null && !allowMissingHandlers) {
                throw new IllegalStateException("No handler registered for tool '%s'".formatted(name));
            }
            registrations.put(name, new RegisteredTool(entry.getValue(), handler));
        }

        if (!handlersByName.isEmpty()) {
            throw new IllegalStateException("Handlers have no matching tool specs: "
                    + String.join(", ", handlersByName.keySet()));
        }

        if (registrations.isEmpty()) {
            throw new IllegalStateException("No tools were registered");
        }

        this.toolsByName = Collections.unmodifiableMap(registrations);
    }

    public Optional<RegisteredTool> find(String name) {
        return Optional.ofNullable(toolsByName.get(name));
    }
}
