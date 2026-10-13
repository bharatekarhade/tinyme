package com.tinyme.tools.handlers;

import com.tinyme.domain.people.service.PersonService;
import com.tinyme.domain.plans.model.PlanAddCommand;
import com.tinyme.domain.plans.model.PlanKind;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class PlansAddTool implements ToolHandler {
    private final PlanService plans;
    private final PersonService people;

    public PlansAddTool(PlanService plans, PersonService people) {
        this.plans = plans;
        this.people = people;
    }

    @Override public String name() { return "plans_add"; }

    @Override
    public ToolResult handle(JsonNode input, ToolContext context) {
        if (input == null || !input.isObject()) return invalid("Input must be an object");
        try {
            PlanKind kind;
            try {
                kind = PlanKind.valueOf(ToolInputs.requiredString(input, "kind").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("kind must be event or task");
            }
            String title = ToolInputs.requiredString(input, "title");
            LocalDate day = ToolInputs.optionalDate(input, "day");
            if (day == null) throw new IllegalArgumentException("day is required");
            LocalTime time = PlanToolInputs.optionalTime(input, "time");
            LocalTime endTime = PlanToolInputs.optionalTime(input, "end_time");
            LocalDate endDay = ToolInputs.optionalDate(input, "end_day");
            String habitKind = ToolInputs.optionalString(input, "habit_kind");
            Integer remind = ToolInputs.optionalInt(input, "remind_minutes");
            List<String> personNames = ToolInputs.optionalStringList(input, "people");
            List<String> personSlugs = PlanToolInputs.resolvePeople(personNames == null ? List.of() : personNames, people);
            PlanAddCommand command = new PlanAddCommand(kind, title, day, time, endTime, endDay,
                    ToolInputs.optionalString(input, "place"), personSlugs,
                    ToolInputs.optionalString(input, "notes"), PlanToolInputs.optionalRepeat(input),
                    habitKind, remind);
            return success(plans.add(command, context.zone(), context.now()));
        } catch (IllegalArgumentException error) {
            return invalid(error.getMessage());
        }
    }

    private static ToolResult success(PlanWriteResult result) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("id", result.id().toString());
        output.put("title", result.title());
        output.put("next", PlanToolOutputs.occurrences(result.occurrences()));
        output.put("conflicts", result.conflicts());
        output.put("warnings", result.warnings());
        String first = result.occurrences().isEmpty() ? "" : " · " + PlanToolOutputs.summaryTime(
                result.occurrences().getFirst().day(), result.occurrences().getFirst().startTime());
        String summary = "Planned · " + result.title() + first;
        return new ToolResult.Ok(output, summary, Map.of("plan_id", result.id().toString()));
    }

    private static ToolResult.Err invalid(String message) {
        return new ToolResult.Err("validation_error", message == null ? "Invalid plan" : message);
    }
}
