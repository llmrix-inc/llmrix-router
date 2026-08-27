package com.llmrix.model.router.core.api.chat;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@EqualsAndHashCode
@Accessors(fluent = true)
public final class ToolCallPart implements ContentPart {
    private final String id;
    private final String name;
    private final String arguments;

    public ToolCallPart(String id, String name, String arguments) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("tool call id must not be blank");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("tool name must not be blank");
        if (arguments == null) throw new IllegalArgumentException("tool arguments must not be null");
        this.id = id;
        this.name = name;
        this.arguments = arguments;
    }

}
