package com.tinyme.tools.handlers;

import com.tinyme.domain.people.model.PersonSnapshot;
import com.tinyme.domain.people.model.Birthday;
import com.tinyme.domain.people.model.upsert.PersonUpsert;
import com.tinyme.domain.people.model.upsert.UpsertOutcome;
import com.tinyme.domain.people.service.PersonService;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolHandler;
import com.tinyme.tools.model.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.time.MonthDay;

@Component
public class PeopleUpsertTool implements ToolHandler {
    private final PersonService people;

    public PeopleUpsertTool(PersonService people) {
        this.people = people;
    }

    @Override
    public String name() {
        return "people_upsert";
    }

    @Override
    public ToolResult handle(JsonNode input, ToolContext context) {
        if (input == null || !input.isObject()) return invalid("Input must be an object");
        try {
            PersonUpsert command = new PersonUpsert(
                    ToolInputs.optionalString(input, "slug"),
                    ToolInputs.requiredString(input, "display_name"),
                    ToolInputs.optionalStringList(input, "aliases"),
                    ToolInputs.optionalString(input, "relationship"),
                    readBirthday(input), input.has("birthday"));
            return switch (people.upsert(command)) {
                case UpsertOutcome.Created created -> ok(created.person(), true);
                case UpsertOutcome.Updated updated -> ok(updated.person(), false);
                case UpsertOutcome.NotFound notFound -> new ToolResult.Err("not_found",
                        "No live person with slug " + notFound.slug());
                case UpsertOutcome.Duplicate duplicate -> duplicate(duplicate.existing());
            };
        } catch (IllegalArgumentException exception) {
            return invalid(exception.getMessage());
        }
    }

    private static ToolResult.Ok ok(PersonSnapshot person, boolean created) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("person", ToolOutputs.person(person));
        output.put("created", created);
        String relationship = person.relationship();
        String summary = created
                ? "Added " + person.displayName() + (relationship == null || relationship.isBlank()
                    ? "" : " (" + relationship + ")")
                : "Updated " + person.displayName();
        return new ToolResult.Ok(output, summary);
    }

    private static ToolResult.Err duplicate(PersonSnapshot existing) {
        String details = "slug " + existing.slug();
        if (existing.relationship() != null && !existing.relationship().isBlank()) {
            details += ", " + existing.relationship();
        }
        return new ToolResult.Err("duplicate", existing.displayName() + " already exists (" + details
                + "). Pass slug to update, or give a relationship that tells them apart.");
    }

    private static ToolResult.Err invalid(String message) {
        return new ToolResult.Err("validation_error", message == null ? "Invalid person" : message);
    }

    private static Birthday readBirthday(JsonNode input) {
        JsonNode birthday = input.get("birthday");
        if (birthday == null || birthday.isNull()) return null;
        if (!birthday.isObject()) throw new IllegalArgumentException("birthday must be an object");
        Integer month = ToolInputs.optionalInt(birthday, "month");
        Integer day = ToolInputs.optionalInt(birthday, "day");
        Integer year = ToolInputs.optionalInt(birthday, "year");
        if (month == null || day == null) throw new IllegalArgumentException("birthday.month and birthday.day are required");
        try {
            return new Birthday(MonthDay.of(month, day), year);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("birthday must be a real calendar date (including in birthday.year)");
        }
    }
}
