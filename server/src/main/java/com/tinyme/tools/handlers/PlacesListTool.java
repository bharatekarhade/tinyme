package com.tinyme.tools.handlers;

import com.tinyme.domain.places.model.PlaceSnapshot;
import com.tinyme.domain.places.service.PlaceService;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolHandler;
import com.tinyme.tools.model.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

@Component
public class PlacesListTool implements ToolHandler {
    private final PlaceService places;
    public PlacesListTool(PlaceService places) { this.places = places; }
    @Override public String name() { return "places_list"; }

    @Override
    public ToolResult handle(JsonNode input, ToolContext context) {
        if (input == null || !input.isObject()) return invalid("Input must be an object");
        try {
            String q = ToolInputs.optionalString(input, "q");
            Boolean includeVisited = ToolInputs.optionalBoolean(input, "include_visited");
            List<PlaceSnapshot> found = places.list(q, Boolean.TRUE.equals(includeVisited));
            List<Map<String, Object>> result = found.stream().map(PlaceToolOutputs::place).toList();
            List<Map<String, Object>> cards = found.stream().limit(PlaceToolOutputs.MAX_CARDS)
                    .map(place -> PlaceToolOutputs.savedCard(place.id().toString(), place.name(),
                            place.area() != null ? place.area() : place.address(), null, place.visited()))
                    .toList();
            String summary = result.isEmpty() ? "No saved places" : "Found " + result.size()
                    + (result.size() == 1 ? " saved place" : " saved places");
            return new ToolResult.Ok(Map.of("places", result), summary,
                    cards.isEmpty() ? Map.of() : Map.of("saved_places", cards, "label", "Your saved places"));
        } catch (IllegalArgumentException invalid) {
            return invalid(invalid.getMessage());
        }
    }

    private static ToolResult.Err invalid(String message) {
        return new ToolResult.Err("validation_error", message == null ? "Invalid places filter" : message);
    }
}
