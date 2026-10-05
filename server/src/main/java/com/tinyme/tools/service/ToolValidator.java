package com.tinyme.tools.service;



import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class ToolValidator {

    private final Map<String, Schema> schemasByToolName;

    public ToolValidator(ToolSpecLoader toolSpecLoader) {
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
                SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemaRegistryConfig(SchemaRegistryConfig.builder()
                        .formatAssertionsEnabled(true)
                        .build()));

        Map<String, Schema> schemas = new LinkedHashMap<>();
        toolSpecLoader.specs().forEach((name, spec) -> {
            Schema schema = registry.getSchema(spec.inputSchema());
            schema.initializeValidators();
            schemas.put(name, schema);
        });
        this.schemasByToolName = Collections.unmodifiableMap(schemas);
    }

    /** Returns no messages when the input conforms to the tool's schema. */
    public List<String> validate(String toolName, JsonNode input) {
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(input, "input");

        Schema schema = schemasByToolName.get(toolName);
        if (schema == null) {
            throw new IllegalArgumentException("Unknown tool '" + toolName + "'");
        }

        return schema.validate(input).stream()
                .map(ToolValidator::formatError)
                .toList();
    }

    private static String formatError(Error error) {
        String location = error.getInstanceLocation().toString();
        return (location.isEmpty() ? "/" : location) + ": " + error.getMessage();
    }
}
