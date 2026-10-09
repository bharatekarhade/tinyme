package com.tinyme.tools.handlers;

import com.tinyme.domain.people.model.PersonSnapshot;
import com.tinyme.domain.people.model.get.PeopleLookup;
import com.tinyme.domain.people.model.get.PersonMatch;
import com.tinyme.domain.people.model.get.PersonMatchType;
import com.tinyme.domain.people.service.PersonService;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PeopleGetToolTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ToolContext CONTEXT = new ToolContext(UUID.randomUUID(), UUID.randomUUID(),
            ZoneId.of("Asia/Tokyo"), LocalDate.of(2026, 10, 9), Instant.parse("2026-10-09T00:00:00Z"));

    private final PersonService service = mock(PersonService.class);
    private final PeopleGetTool tool = new PeopleGetTool(service);

    @Test
    void blankQueryReturnsValidationErrorWithoutCallingService() {
        ToolResult result = tool.handle(JSON.readTree("{\"query\":\"   \"}"), null);

        assertThat(result).isEqualTo(new ToolResult.Err("validation_error", "query is required"));
        verify(service, never()).get(any());
    }

    @Test
    void noMatchSummaryIncludesTheStrippedQuery() {
        when(service.get("Kenzo")).thenReturn(new PeopleLookup(PersonMatchType.NONE, List.of()));

        ToolResult.Ok result = (ToolResult.Ok) tool.handle(JSON.readTree("{\"query\":\" Kenzo \"}"), null);

        assertThat(result.summary()).isEqualTo("No one named \"Kenzo\"");
        verify(service).get("Kenzo");
    }

    @Test
    void oneMatchSummaryIncludesRelationshipAndOutputDetails() {
        PersonSnapshot person = new PersonSnapshot("kenji", "Kenji", List.of("Ken"),
                "friend from work", "people/kenji.md");
        when(service.get("Ken")).thenReturn(new PeopleLookup(PersonMatchType.EXACT,
                List.of(new PersonMatch(person, Instant.parse("2026-10-08T12:30:05.987Z")))));

        ToolResult.Ok result = (ToolResult.Ok) tool.handle(JSON.readTree("{\"query\":\"Ken\"}"), CONTEXT);

        assertThat(result.summary()).isEqualTo("Found Kenji (friend from work)");
        var output = JSON.valueToTree(result.data());
        assertThat(output.get("match").stringValue()).isEqualTo("exact");
        assertThat(output.get("matches").get(0).get("slug").stringValue()).isEqualTo("kenji");
        assertThat(output.get("matches").get(0).get("last_seen").stringValue())
                .isEqualTo("2026-10-08T21:30:05+09:00");
    }

    @Test
    void multipleMatchSummaryAsksForDisambiguation() {
        PersonSnapshot friend = new PersonSnapshot("kenji", "Kenji", List.of("Ken"),
                "friend from work", "people/kenji.md");
        PersonSnapshot cousin = new PersonSnapshot("kenji-2", "Kenji", List.of(),
                "cousin", "people/kenji-2.md");
        when(service.get("Ken")).thenReturn(new PeopleLookup(PersonMatchType.EXACT, List.of(
                new PersonMatch(friend, null), new PersonMatch(cousin, null))));

        ToolResult.Ok result = (ToolResult.Ok) tool.handle(JSON.readTree("{\"query\":\"Ken\"}"), CONTEXT);

        assertThat(result.summary()).isEqualTo("2 people match \"Ken\"");
    }

    @Test
    void queryLongerThanSchemaMaximumReturnsValidationError() {
        ToolResult.Err result = (ToolResult.Err) tool.handle(JSON.valueToTree(
                java.util.Map.of("query", "x".repeat(81))), null);

        assertThat(result.code()).isEqualTo("validation_error");
        assertThat(result.message()).isEqualTo("query must be at most 80 characters");
        verify(service, never()).get(any());
    }
}
