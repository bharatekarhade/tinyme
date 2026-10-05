package com.tinyme.tools.model;



public sealed interface ToolResult permits  ToolResult.Ok, ToolResult.Err {
    record Ok(Object data, String summary) implements ToolResult {}
    record Err(String code, String message) implements ToolResult {}
}
