package com.tinyme.tools.service;



import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolSpecLoaderTests {
    @Test
    void loadsTheCustomToolsFromThePackagedAgentSeed() {
        var specs = new ToolSpecLoader().specs();

        assertThat(specs.keySet()).containsExactlyInAnyOrder(
                "entries_add", "entries_query", "entries_aggregate", "entries_update",
                "entries_delete", "people_get", "people_upsert");
        assertThat(specs.values()).allSatisfy(spec ->
                assertThat(spec.inputSchema().get("type").stringValue()).isEqualTo("object"));
        assertThat(strings(specs.get("entries_add").inputSchema().get("required")))
                .containsExactly("kind");
        assertThat(specs.get("entries_add").inputSchema().get("properties").has("people")).isFalse();
        assertThat(specs.get("entries_query").inputSchema().get("properties").has("where")).isTrue();
        assertThat(specs.get("entries_query").inputSchema().get("properties").has("people")).isFalse();
    }

    @Test
    void rejectsDuplicateCustomToolNames() {
        assertThatThrownBy(() -> new ToolSpecLoader("broken/duplicate-tools.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate custom tool name 'same'");
    }

    @Test
    void rejectsCustomToolWithoutInputSchema() {
        assertThatThrownBy(() -> new ToolSpecLoader("broken/missing-input-schema.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Custom tool 'broken' in 'broken/missing-input-schema.yaml' is missing input_schema");
    }

    @Test
    void rejectsSeedWithoutCustomTools() {
        assertThatThrownBy(() -> new ToolSpecLoader("broken/no-custom-tools.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("contains no custom tools");
    }

    private static java.util.List<String> strings(JsonNode array) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false)
                .map(JsonNode::stringValue).toList();
    }
}
