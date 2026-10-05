package com.tinyme.tools.service;

import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolHandler;
import com.tinyme.tools.model.ToolResult;
import com.tinyme.tools.model.ToolSpec;


import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolRegistryTests {

    @Test
    void findsRegisteredToolByName() throws Exception {
        ToolSpec spec = spec("entries_add");
        ToolHandler handler = handler("entries_add");
        ToolRegistry registry = new ToolRegistry(loader(Map.of("entries_add", spec)), List.of(handler), false);

        assertThat(registry.find("entries_add"))
                .hasValueSatisfying(registered -> {
                    assertThat(registered.toolSpec()).isSameAs(spec);
                    assertThat(registered.toolHandler()).isSameAs(handler);
                    assertThat(registered.implemented()).isTrue();
                });
        assertThat(registry.find("missing_tool")).isEmpty();
    }

    @Test
    void permitsMissingHandlerWhenConfigured() throws Exception {
        ToolSpec spec = spec("entries_add");

        ToolRegistry registry = new ToolRegistry(loader(Map.of("entries_add", spec)), List.of(), true);

        assertThat(registry.find("entries_add"))
                .hasValueSatisfying(registered -> {
                    assertThat(registered.toolSpec()).isSameAs(spec);
                    assertThat(registered.toolHandler()).isNull();
                    assertThat(registered.implemented()).isFalse();
                });
    }

    @Test
    void rejectsMissingHandlerByDefault() throws Exception {
        assertThatThrownBy(() -> new ToolRegistry(
                loader(Map.of("entries_add", spec("entries_add"))), List.of(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No handler registered for tool 'entries_add'");
    }

    @Test
    void rejectsDuplicateHandlerNames() throws Exception {
        assertThatThrownBy(() -> new ToolRegistry(
                loader(Map.of("entries_add", spec("entries_add"))),
                List.of(handler("entries_add"), handler("entries_add")),
                false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Duplicate tool handler name 'entries_add'");
    }

    @Test
    void rejectsHandlersWithoutMatchingSpecs() throws Exception {
        assertThatThrownBy(() -> new ToolRegistry(
                loader(Map.of("entries_add", spec("entries_add"))),
                List.of(handler("unknown_tool")),
                true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Handlers have no matching tool specs: unknown_tool");
    }

    @Test
    void rejectsEmptyToolSpecMap() {
        assertThatThrownBy(() -> new ToolRegistry(loader(Map.of()), List.of(), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No tools were registered");
    }

    private static ToolSpecLoader loader(Map<String, ToolSpec> specs) {
        ToolSpecLoader loader = mock(ToolSpecLoader.class);
        when(loader.specs()).thenReturn(specs);
        return loader;
    }

    private static ToolSpec spec(String name) throws Exception {
        JsonNode schema = YAMLMapper.builder().build().readTree("type: object");
        return new ToolSpec(name, schema);
    }

    private static ToolHandler handler(String name) {
        return new ToolHandler() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ToolResult handle(JsonNode jsonNode, ToolContext context) {
                return new ToolResult.Ok(null, "handled");
            }
        };
    }

}
