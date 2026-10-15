package com.tinyme.tools.handlers;

import com.tinyme.domain.places.service.PlaceService;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolHandler;
import com.tinyme.tools.model.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

@Component
public class PlacesRemoveTool implements ToolHandler {
    private final PlaceService places;
    public PlacesRemoveTool(PlaceService places) { this.places = places; }
    @Override public String name() { return "places_remove"; }

    @Override
    public ToolResult handle(JsonNode input, ToolContext context) {
        if (input == null || !input.isObject()) return invalid("Input must be an object");
        try {
            UUID id = ToolInputs.requiredUuid(input, "id");
            var removed = places.remove(id, context.now());
            if (removed.isEmpty()) return new ToolResult.Err("not_found", "No saved place found with that id");
            return new ToolResult.Ok(PlaceToolOutputs.place(removed.get()), "Removed " + removed.get().name());
        } catch (IllegalArgumentException invalid) {
            return invalid(invalid.getMessage());
        }
    }

    private static ToolResult.Err invalid(String message) {
        return new ToolResult.Err("validation_error", message == null ? "Invalid place id" : message);
    }
}
