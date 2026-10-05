package com.tinyme.tools.model;



import tools.jackson.databind.JsonNode;

import java.util.Objects;

public record ToolSpec(String name, JsonNode inputSchema) {
    public ToolSpec{
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(inputSchema, "inputSchema");
    }
}
