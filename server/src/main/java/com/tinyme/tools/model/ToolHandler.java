package com.tinyme.tools.model;




import tools.jackson.databind.JsonNode;

public interface ToolHandler {
    String name();
    ToolResult handle(JsonNode jsonNode, ToolContext ctx);
}
