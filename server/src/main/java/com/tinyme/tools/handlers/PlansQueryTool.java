package com.tinyme.tools.handlers;

import com.tinyme.domain.plans.model.PlanKind;
import com.tinyme.domain.plans.model.PlanQuery;
import com.tinyme.domain.plans.service.PlanService;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolHandler;
import com.tinyme.tools.model.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Component
public class PlansQueryTool implements ToolHandler {
    private final PlanService plans;

    public PlansQueryTool(PlanService plans) { this.plans = plans; }

    @Override public String name() { return "plans_query"; }

    @Override
    public ToolResult handle(JsonNode input, ToolContext context) {
        if (input == null || !input.isObject()) return invalid("Input must be an object");
        try {
            LocalDate from = ToolInputs.optionalDate(input, "from");
            LocalDate to = ToolInputs.optionalDate(input, "to");
            if (from == null || to == null) throw new IllegalArgumentException("from and to are required");
            String kindValue = ToolInputs.optionalString(input, "kind");
            PlanKind kind = null;
            if (kindValue != null) {
                try {
                    kind = PlanKind.valueOf(kindValue.strip().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException error) {
                    throw new IllegalArgumentException("kind must be event or task");
                }
            }
            PlanQuery query = new PlanQuery(from, to, kind, ToolInputs.optionalString(input, "q"),
                    ToolInputs.optionalString(input, "person"), Boolean.TRUE.equals(ToolInputs.optionalBoolean(input, "include_done")));
            var result = plans.query(query, context.zone(), context.now());
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("occurrences", PlanToolOutputs.occurrences(result.occurrences()));
            output.put("truncated", result.truncated());
            String summary = result.occurrences().isEmpty()
                    ? "No plans in that period"
                    : "Found " + result.occurrences().size() + " plan occurrence" + (result.occurrences().size() == 1 ? "" : "s");
            return new ToolResult.Ok(output, summary);
        } catch (IllegalArgumentException error) {
            return invalid(error.getMessage());
        }
    }

    private static ToolResult.Err invalid(String message) {
        return new ToolResult.Err("validation_error", message == null ? "Invalid plan query" : message);
    }
}
