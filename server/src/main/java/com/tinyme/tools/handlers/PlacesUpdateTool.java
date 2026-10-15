package com.tinyme.tools.handlers;

import com.tinyme.domain.places.service.PlaceService;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolHandler;
import com.tinyme.tools.model.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.UUID;

@Component
public class PlacesUpdateTool implements ToolHandler {
    private final PlaceService places;
    public PlacesUpdateTool(PlaceService places) { this.places = places; }
    @Override public String name() { return "places_update"; }

    @Override
    public ToolResult handle(JsonNode input, ToolContext context) {
        if (input == null || !input.isObject()) return invalid("Input must be an object");
        try {
            UUID id = ToolInputs.requiredUuid(input, "id");
            String note = ToolInputs.optionalString(input, "note");
            if (note != null && note.length() > PlaceService.MAX_NOTE_LENGTH) {
                throw new IllegalArgumentException("note must be at most 300 characters");
            }
            Boolean visited = ToolInputs.optionalBoolean(input, "visited");
            var updated = places.update(id, input.has("note"), note, visited, context.now());
            if (updated.isEmpty()) return new ToolResult.Err("not_found", "No saved place found with that id");
            return new ToolResult.Ok(PlaceToolOutputs.place(updated.get()), updated.get().visited()
                    ? "Marked " + updated.get().name() + " visited" : "Updated " + updated.get().name());
        } catch (IllegalArgumentException invalid) {
            return invalid(invalid.getMessage());
        }
    }

    private static ToolResult.Err invalid(String message) {
        return new ToolResult.Err("validation_error", message == null ? "Invalid place update" : message);
    }
}
