package com.llmrix.model.router.core.api;

public record ToolCallPart(String id, String name, String arguments) implements ContentPart {
    public ToolCallPart {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("tool call id must not be blank");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("tool name must not be blank");
        if (arguments == null) throw new IllegalArgumentException("tool arguments must not be null");
    }
}
