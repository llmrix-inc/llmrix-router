package com.llmrix.model.router.core.api.chat;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.Map;

@Getter
@EqualsAndHashCode
@Accessors(fluent = true)
public final class ToolDefinition {
    private final String name;
    private final String description;
    private final Map<String, Object> parameters;
    private final boolean strict;

    public ToolDefinition(String name, String description, Map<String, Object> parameters, boolean strict) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("tool name must not be blank");
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        this.name = name;
        this.description = description;
        this.parameters = parameters;
        this.strict = strict;
    }

    public ToolDefinition(String name, String description, Map<String, Object> parameters) {
        this(name, description, parameters, false);
    }

}
