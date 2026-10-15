package com.tinyme.tools.handlers;

import com.tinyme.domain.places.model.PlaceSaveResult;
import com.tinyme.domain.places.service.PlaceService;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolHandler;
import com.tinyme.tools.model.ToolResult;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class PlacesSaveTool implements ToolHandler {
    private final PlaceService places;
    public PlacesSaveTool(PlaceService places) { this.places = places; }
    @Override public String name() { return "places_save"; }

    @Override
    public ToolResult handle(JsonNode input, ToolContext context) {
        if (input == null || !input.isObject()) return invalid("Input must be an object");
        try {
            String candidateId = ToolInputs.requiredString(input, "candidate_id").strip();
            String note = ToolInputs.optionalString(input, "note");
            if (note != null && note.length() > PlaceService.MAX_NOTE_LENGTH) {
                throw new IllegalArgumentException("note must be at most 300 characters");
            }
            PlaceSaveResult saved;
            try {
                saved = places.saveCandidate(candidateId, note, context.now());
            } catch (DataIntegrityViolationException raced) {
                // Another save of the same place won; this one now finds it and returns it as already saved.
                saved = places.saveCandidate(candidateId, note, context.now());
            }
            Map<String, Object> output = new LinkedHashMap<>(PlaceToolOutputs.place(saved.place()));
            output.put("already_saved", saved.alreadySaved());
            return new ToolResult.Ok(output, saved.alreadySaved()
                    ? "That place is already saved" : "Saved " + saved.place().name(),
                    Map.of("place_id", saved.place().id().toString()));
        } catch (PlaceService.CandidateNotFoundException missing) {
            return invalid("That place search result is unknown or expired; search again");
        } catch (IllegalArgumentException invalid) {
            return invalid(invalid.getMessage());
        }
    }

    private static ToolResult.Err invalid(String message) {
        return new ToolResult.Err("validation_error", message == null ? "Invalid place candidate" : message);
    }
}
