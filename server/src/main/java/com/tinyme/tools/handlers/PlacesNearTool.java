package com.tinyme.tools.handlers;

import com.tinyme.domain.places.service.PlaceService;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolHandler;
import com.tinyme.tools.model.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

@Component
public class PlacesNearTool implements ToolHandler {
    private final PlaceService places;
    public PlacesNearTool(PlaceService places) { this.places = places; }
    @Override public String name() { return "places_near"; }

    @Override
    public ToolResult handle(JsonNode input, ToolContext context) {
        if (input == null || !input.isObject()) return invalid("Input must be an object");
        try {
            if (context.location() == null) {
                return new ToolResult.Err("no_location", "Turn on location for TinyMe, or tell me where you are");
            }
            Integer radius = ToolInputs.optionalInt(input, "radius_m");
            var found = places.near(context.location().lat(), context.location().lng(), radius == null ? 1_000 : radius);
            List<Map<String, Object>> result = found.stream().map(PlaceToolOutputs::nearby).toList();
            List<Map<String, Object>> cards = found.stream().limit(PlaceToolOutputs.MAX_CARDS)
                    .map(place -> PlaceToolOutputs.savedCard(place.id().toString(), place.name(), place.address(),
                            place.distanceM(), place.visitedAt() != null))
                    .toList();
            return new ToolResult.Ok(Map.of("places", result), result.isEmpty()
                    ? "No saved places nearby" : "Found " + result.size() + " saved nearby "
                    + (result.size() == 1 ? "place" : "places"),
                    cards.isEmpty() ? Map.of() : Map.of("saved_places", cards, "label", "Saved near you"));
        } catch (IllegalArgumentException invalid) {
            return invalid(invalid.getMessage());
        }
    }

    private static ToolResult.Err invalid(String message) {
        return new ToolResult.Err("validation_error", message == null ? "Invalid nearby search" : message);
    }
}
