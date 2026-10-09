package com.tinyme.tools.service;

import com.tinyme.tools.model.ToolSpec;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolValidatorTests {

    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    @Test
    void compilesAllPackagedToolSchemas() {
        assertThat(new ToolValidator(new ToolSpecLoader())).isNotNull();
    }

    @Test
    void validatesInputsAgainstThePackagedEntriesAddSchema() throws Exception {
        ToolValidator validator = new ToolValidator(new ToolSpecLoader());

        assertThat(validator.validate("entries_add", YAML.readTree("{kind: drink}"))).isEmpty();
        assertThat(validator.validate("entries_add", YAML.readTree("{}")))
                .anySatisfy(message -> assertThat(message).contains("required"));
        assertThat(validator.validate("entries_add", YAML.readTree("{kind: 'Drink!'}")))
                .anySatisfy(message -> assertThat(message).contains("pattern"));
        assertThat(validator.validate("entries_add", YAML.readTree("{kind: drink, people: [kenji]}")))
                .anySatisfy(message -> assertThat(message).contains("people"));
        assertThat(validator.validate("entries_add", YAML.readTree("{kind: drink, data: {type: coffee}}")))
                .isEmpty();
    }

    @Test
    void validatesPeopleGetQueryLengthAndRejectsUnknownProperties() throws Exception {
        ToolValidator validator = new ToolValidator(new ToolSpecLoader());

        assertThat(validator.validate("people_get", YAML.readTree("{query: Ken}"))).isEmpty();
        assertThat(validator.validate("people_get", YAML.readTree("{query: Ken, extra: true}")))
                .anySatisfy(message -> assertThat(message).contains("extra"));
        assertThat(validator.validate("people_get", YAML.readTree("{query: '" + "x".repeat(81) + "'}")))
                .anySatisfy(message -> assertThat(message).contains("80 characters"));
    }

    @Test
    void acceptsValidInputAndDoesNotApplySchemaDefaults() throws Exception {
        JsonNode schema = YAML.readTree("""
                type: object
                properties:
                  quantity:
                    type: integer
                    default: 1
                additionalProperties: false
                """);
        JsonNode input = YAML.readTree("{}");
        ToolValidator validator = validator(Map.of("entries_add", new ToolSpec("entries_add", schema)));

        assertThat(validator.validate("entries_add", input)).isEmpty();
        assertThat(input.has("quantity")).isFalse();
    }

    @Test
    void reportsSchemaViolationsWithInstanceLocations() throws Exception {
        JsonNode schema = YAML.readTree("""
                type: object
                required: [kind]
                properties:
                  kind:
                    type: string
                """);
        ToolValidator validator = validator(Map.of("entries_add", new ToolSpec("entries_add", schema)));

        var errors = validator.validate("entries_add", YAML.readTree("{}"));
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst()).contains("/").contains("required");
    }

    @Test
    void enforcesDateTimeFormatAssertions() throws Exception {
        JsonNode schema = YAML.readTree("""
                type: object
                required: [occurred_at]
                properties:
                  occurred_at:
                    type: string
                    format: date-time
                """);
        ToolValidator validator = validator(Map.of("events_add", new ToolSpec("events_add", schema)));

        assertThat(validator.validate("events_add",
                YAML.readTree("occurred_at: not-a-date-time")))
                .anySatisfy(message -> assertThat(message).contains("date-time"));
        assertThat(validator.validate("events_add",
                YAML.readTree("occurred_at: '2026-10-05T12:30:00Z'"))).isEmpty();
    }

    @Test
    void rejectsUnknownToolNames() throws Exception {
        JsonNode schema = YAML.readTree("type: object");
        ToolValidator validator = validator(Map.of("entries_add", new ToolSpec("entries_add", schema)));

        assertThatThrownBy(() -> validator.validate("missing_tool", YAML.readTree("{}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown tool 'missing_tool'");
    }

    private static ToolValidator validator(Map<String, ToolSpec> specs) {
        ToolSpecLoader loader = mock(ToolSpecLoader.class);
        when(loader.specs()).thenReturn(specs);
        return new ToolValidator(loader);
    }
}
