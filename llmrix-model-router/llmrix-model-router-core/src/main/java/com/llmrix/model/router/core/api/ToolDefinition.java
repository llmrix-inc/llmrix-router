package com.llmrix.model.router.core.api;

import java.util.Map;

public record ToolDefinition(String name, String description, Map<String, Object> parameters, boolean strict) {
    public ToolDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("tool name must not be blank");
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }

    public ToolDefinition(String name, String description, Map<String, Object> parameters) {
        this(name, description, parameters, false);
    }
}
