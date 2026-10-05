package com.tinyme.tools.service;

import com.tinyme.tools.model.ToolSpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Component
public class ToolSpecLoader {
    private static final String DEFAULT_SEED_PATH = "seed/agents/chat.yaml";

    private final Map<String, ToolSpec> specs;

    @Autowired
    public ToolSpecLoader(){
        this(DEFAULT_SEED_PATH);
    }

    ToolSpecLoader(String yamlPath) {
        Objects.requireNonNull(yamlPath, "yamlPath");
        try (InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(yamlPath)) {
            if (input == null) {
                throw new IllegalStateException("Tool seed YAML not found on the classpath: " + yamlPath);
            }
            this.specs = load(YAMLMapper.builder().build().readTree(input), yamlPath);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read tool seed YAML '" + yamlPath + "': "
                    + exception.getMessage(), exception);
        }
    }

    public Map<String, ToolSpec> specs() {
        return specs;
    }

    private static Map<String, ToolSpec> load(JsonNode root, String path) {
        JsonNode tools = root == null ? null : root.get("tools");
        if (tools == null || !tools.isArray()) {
            throw new IllegalStateException("Tool seed YAML '" + path + "' must contain a tools array");
        }

        Map<String, ToolSpec> result = new LinkedHashMap<>();
        for (JsonNode tool : tools) {
            JsonNode type = tool.get("type");
            if (type == null || !type.isString() || !"custom".equals(type.stringValue())) {
                continue;
            }

            JsonNode nameNode = tool.get("name");
            if (nameNode == null || !nameNode.isString() || nameNode.stringValue().isBlank()) {
                throw new IllegalStateException("Custom tool in '" + path + "' is missing a non-empty name");
            }
            String name = nameNode.stringValue();
            JsonNode schema = tool.get("input_schema");
            if (schema == null || schema.isNull()) {
                throw new IllegalStateException("Custom tool '" + name + "' in '" + path
                        + "' is missing input_schema");
            }
            if (!schema.isObject()) {
                throw new IllegalStateException("Custom tool '" + name + "' in '" + path
                        + "' must have an object input_schema");
            }
            if (result.putIfAbsent(name, new ToolSpec(name, schema)) != null) {
                throw new IllegalStateException("Duplicate custom tool name '" + name + "' in '" + path + "'");
            }
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("Tool seed YAML '" + path + "' contains no custom tools");
        }
        return Collections.unmodifiableMap(result);
    }
}
