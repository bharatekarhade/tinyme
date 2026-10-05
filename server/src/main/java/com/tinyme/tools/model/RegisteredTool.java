package com.tinyme.tools.model;




import java.util.Objects;

public record RegisteredTool(ToolSpec toolSpec, ToolHandler toolHandler) {

    public RegisteredTool {
        Objects.requireNonNull(toolSpec, "toolSpec");

        if (toolHandler != null && !toolSpec.name().equals(toolHandler.name())) {
            throw new IllegalArgumentException(
                    "Handler name '%s' does not match tool spec name '%s'"
                            .formatted(toolHandler.name(), toolSpec.name()));
        }
    }

    public String name() {
        return toolSpec.name();
    }

    public boolean implemented() {
        return toolHandler != null;
    }
}
