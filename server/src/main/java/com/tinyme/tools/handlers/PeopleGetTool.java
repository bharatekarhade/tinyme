package com.tinyme.tools.handlers;

import com.tinyme.domain.people.model.get.PeopleLookup;
import com.tinyme.domain.people.model.get.PersonMatch;
import com.tinyme.domain.people.service.PersonService;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolHandler;
import com.tinyme.tools.model.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

@Component
public class PeopleGetTool implements ToolHandler {
    private static final int MAX_QUERY_LENGTH = 80;

    private final PersonService people;

    public PeopleGetTool(PersonService people) {
        this.people = people;
    }

    @Override
    public String name() {
        return "people_get";
    }

    @Override
    public ToolResult handle(JsonNode input, ToolContext context) {
        if (input == null || !input.isObject()) return invalid("Input must be an object");
        try {
            String query = ToolInputs.requiredString(input, "query").strip();
            if (query.codePointCount(0, query.length()) > MAX_QUERY_LENGTH) {
                throw new IllegalArgumentException("query must be at most %d characters".formatted(MAX_QUERY_LENGTH));
            }
            PeopleLookup lookup = people.get(query);
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("match", lookup.match().name().toLowerCase(Locale.ROOT));
            output.put("matches", lookup.matches().stream()
                    .map(match -> ToolOutputs.personMatch(match, context.zone())).toList());
            return new ToolResult.Ok(output, summary(query, lookup));
        } catch (IllegalArgumentException exception) {
            return invalid(exception.getMessage());
        }
    }

    private static String summary(String query, PeopleLookup lookup) {
        List<PersonMatch> matches = lookup.matches();
        if (matches.isEmpty()) return "No one named \"" + query + "\"";
        if (matches.size() > 1) return matches.size() + " people match \"" + query + "\"";

        PersonMatch match = matches.getFirst();
        String relationship = match.person().relationship();
        return relationship == null || relationship.isBlank()
                ? "Found " + match.person().displayName()
                : "Found " + match.person().displayName() + " (" + relationship + ")";
    }

    private static ToolResult.Err invalid(String message) {
        return new ToolResult.Err("validation_error", message == null ? "Invalid person query" : message);
    }
}
