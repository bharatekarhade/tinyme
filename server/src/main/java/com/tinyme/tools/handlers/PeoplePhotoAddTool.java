package com.tinyme.tools.handlers;

import com.tinyme.domain.people.service.PersonPhotoLinkService;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolHandler;
import com.tinyme.tools.model.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/** Saves the image attached to the current user message into a person's photo gallery. */
@Component
public class PeoplePhotoAddTool implements ToolHandler {
    private final PersonPhotoLinkService photos;

    public PeoplePhotoAddTool(PersonPhotoLinkService photos) {
        this.photos = photos;
    }

    @Override
    public String name() {
        return "people_photo_add";
    }

    @Override
    public ToolResult handle(JsonNode input, ToolContext context) {
        if (input == null || !input.isObject()) return invalid("Input must be an object");
        if (context.attachedPhotoId() == null) {
            return invalid("There is no photo attached to this message; attach the photo you want to save");
        }
        try {
            String slug = ToolInputs.requiredString(input, "slug").strip();
            if (slug.isBlank()) return invalid("slug is required");
            String displayName = photos.addToProfile(slug, context.attachedPhotoId());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("slug", slug);
            data.put("photo_id", context.attachedPhotoId());
            return new ToolResult.Ok(data, "Added photo to " + displayName + "'s profile");
        } catch (PersonPhotoLinkService.PersonNotFoundException missing) {
            return new ToolResult.Err("not_found", "No live person with slug " + missing.getMessage());
        } catch (PersonPhotoLinkService.PhotoUnavailableException missing) {
            return new ToolResult.Err("not_found", missing.getMessage());
        } catch (IllegalArgumentException invalid) {
            return invalid(invalid.getMessage());
        }
    }

    private static ToolResult.Err invalid(String message) {
        return new ToolResult.Err("validation_error", message == null ? "Invalid photo request" : message);
    }
}
