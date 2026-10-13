package com.tinyme.tools.handlers;

import com.tinyme.domain.people.service.PersonService;
import com.tinyme.domain.plans.model.PlanChange;
import com.tinyme.domain.plans.model.PlanWriteResult;
import com.tinyme.domain.plans.service.PlanService;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolHandler;
import com.tinyme.tools.model.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class PlansUpdateTool implements ToolHandler {
    private static final List<String> EDIT_FIELDS = List.of("title", "day", "time", "end_time", "end_day",
            "place", "people", "notes", "repeat", "habit_kind", "remind_minutes");

    private final PlanService plans;
    private final PersonService people;

    public PlansUpdateTool(PlanService plans, PersonService people) {
        this.plans = plans;
        this.people = people;
    }

    @Override public String name() { return "plans_update"; }

    @Override
    public ToolResult handle(JsonNode input, ToolContext context) {
        if (input == null || !input.isObject()) return invalid("Input must be an object");
        try {
            UUID id = ToolInputs.requiredUuid(input, "id");
            LocalDate occurrenceDay = ToolInputs.optionalDate(input, "occurrence_day");
            String action = ToolInputs.optionalString(input, "action");
            PlanChange change;
            if (action != null) {
                action = action.strip().toLowerCase(Locale.ROOT);
                Set<String> suppliedFields = suppliedFields(input);
                if ("move".equals(action)) suppliedFields.remove("day");
                if ("move".equals(action)) suppliedFields.remove("time");
                if (!suppliedFields.isEmpty()) {
                    throw new IllegalArgumentException("action cannot be combined with plan field edits");
                }
                change = switch (action) {
                    case "done" -> new PlanChange.Done(occurrenceDay);
                    case "undo_done" -> new PlanChange.UndoDone(occurrenceDay);
                    case "skip" -> new PlanChange.Skip(requiredDate(input, "occurrence_day"));
                    case "move" -> new PlanChange.Move(requiredDate(input, "occurrence_day"),
                            ToolInputs.optionalDate(input, "day"),
                            PlanToolInputs.optionalTime(input, "time"));
                    case "cancel" -> new PlanChange.Cancel();
                    default -> throw new IllegalArgumentException("action must be done, undo_done, skip, move or cancel");
                };
            } else {
                if (occurrenceDay != null) {
                    throw new IllegalArgumentException("To change only one day, use action: move or skip");
                }
                Set<String> fields = suppliedFields(input);
                if (fields.isEmpty()) throw new IllegalArgumentException("provide an action or at least one plan field");
                String habitKind = ToolInputs.optionalString(input, "habit_kind");
                List<String> rawPeople = ToolInputs.optionalStringList(input, "people");
                List<String> personSlugs = fields.contains("people")
                        ? PlanToolInputs.resolvePeople(rawPeople == null ? List.of() : rawPeople, people) : List.of();
                change = new PlanChange.Edit(fields,
                        ToolInputs.optionalString(input, "title"), ToolInputs.optionalDate(input, "day"),
                        PlanToolInputs.optionalTime(input, "time"), PlanToolInputs.optionalTime(input, "end_time"),
                        ToolInputs.optionalDate(input, "end_day"), ToolInputs.optionalString(input, "place"),
                        personSlugs, ToolInputs.optionalString(input, "notes"),
                        fields.contains("repeat") ? PlanToolInputs.optionalRepeat(input) : null,
                        habitKind, ToolInputs.optionalInt(input, "remind_minutes"));
            }

            var result = plans.update(id, change, context.zone(), context.now());
            if (result.isEmpty()) return new ToolResult.Err("not_found", "No live plan with id " + id);
            return success(result.get(), action);
        } catch (IllegalArgumentException error) {
            return invalid(error.getMessage());
        }
    }

    private static Set<String> suppliedFields(JsonNode input) {
        Set<String> fields = new LinkedHashSet<>();
        for (String field : EDIT_FIELDS) if (input.has(field)) fields.add(field);
        return fields;
    }

    private static LocalDate requiredDate(JsonNode input, String key) {
        LocalDate value = ToolInputs.optionalDate(input, key);
        if (value == null) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private static ToolResult.Ok success(PlanWriteResult result, String action) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("id", result.id().toString());
        output.put("title", result.title());
        output.put("occurrences", PlanToolOutputs.occurrences(result.occurrences()));
        String summary = action == null ? "Updated · " + result.title()
                : switch (action) {
                    case "done" -> "Done · " + result.title();
                    case "undo_done" -> "Marked not done · " + result.title();
                    case "skip" -> "Skipped · " + result.title();
                    case "move" -> "Moved · " + result.title();
                    case "cancel" -> "Cancelled · " + result.title();
                    default -> "Updated · " + result.title();
                };
        return new ToolResult.Ok(output, summary, Map.of("plan_id", result.id().toString()));
    }

    private static ToolResult.Err invalid(String message) {
        return new ToolResult.Err("validation_error", message == null ? "Invalid plan update" : message);
    }
}
