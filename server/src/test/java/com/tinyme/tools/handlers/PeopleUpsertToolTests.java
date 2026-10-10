package com.tinyme.tools.handlers;

import com.tinyme.domain.people.model.PersonSnapshot;
import com.tinyme.domain.people.model.upsert.PersonUpsert;
import com.tinyme.domain.people.model.upsert.UpsertOutcome;
import com.tinyme.domain.people.service.PersonService;
import com.tinyme.tools.model.ToolResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PeopleUpsertToolTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final PersonSnapshot PERSON = new PersonSnapshot("kenji", "Kenji", List.of("Ken"),
            "friend from work", "people/kenji.md");

    private final PersonService service = mock(PersonService.class);
    private final PeopleUpsertTool tool = new PeopleUpsertTool(service);

    @Test
    void createdOutcomeReturnsPersonAndAddedSummary() throws Exception {
        when(service.upsert(any())).thenReturn(new UpsertOutcome.Created(PERSON));

        ToolResult.Ok result = (ToolResult.Ok) tool.handle(JSON.readTree("""
                {"display_name":"Kenji", "aliases":["Ken"], "relationship":"friend from work"}
                """), null);

        assertThat(result.summary()).isEqualTo("Added Kenji (friend from work)");
        assertThat(JSON.writeValueAsString(result.data())).startsWith("{\"person\":");
        assertThat(JSON.valueToTree(result.data()).get("created").booleanValue()).isTrue();
        assertThat(JSON.valueToTree(result.data()).get("person").get("memory_path").stringValue())
                .isEqualTo("people/kenji.md");
        verify(service).upsert(new PersonUpsert(null, "Kenji", List.of("Ken"), "friend from work"));
    }

    @Test
    void updatedOutcomeReturnsUpdatedSummary() {
        when(service.upsert(any())).thenReturn(new UpsertOutcome.Updated(PERSON));

        ToolResult.Ok result = (ToolResult.Ok) tool.handle(
                JSON.readTree("{\"slug\":\"kenji\",\"display_name\":\"Kenji\"}"), null);

        assertThat(result.summary()).isEqualTo("Updated Kenji");
        assertThat(JSON.valueToTree(result.data()).get("created").booleanValue()).isFalse();
    }

    @Test
    void notFoundAndDuplicateOutcomesMapToClearErrors() {
        when(service.upsert(any())).thenReturn(new UpsertOutcome.NotFound("missing"));
        ToolResult.Err missing = (ToolResult.Err) tool.handle(JSON.readTree(
                "{\"slug\":\"missing\",\"display_name\":\"Kenji\"}"), null);
        assertThat(missing.code()).isEqualTo("not_found");

        when(service.upsert(any())).thenReturn(new UpsertOutcome.Duplicate(PERSON));
        ToolResult.Err duplicate = (ToolResult.Err) tool.handle(JSON.readTree("{\"display_name\":\"Kenji\"}"), null);
        assertThat(duplicate.code()).isEqualTo("duplicate");
        assertThat(duplicate.message()).contains("slug kenji, friend from work", "Pass slug to update");
    }

    @Test
    void malformedAliasesBecomeValidationError() {
        ToolResult.Err result = (ToolResult.Err) tool.handle(
                JSON.readTree("{\"display_name\":\"Kenji\",\"aliases\":[1]}"), null);

        assertThat(result.code()).isEqualTo("validation_error");
        assertThat(result.message()).isEqualTo("aliases must contain only strings");
    }
}
