package com.llmrix.model.router.core.api.chat;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@EqualsAndHashCode
@Accessors(fluent = true)
public final class ToolCallDelta {
    private final int index;
    private final String id;
    private final String name;
    private final String arguments;

    public ToolCallDelta(int index, String id, String name, String arguments) {
        if (index < 0) throw new IllegalArgumentException("tool call index must be >= 0");
        if (id != null && id.isBlank()) id = null;
        if (name != null && name.isBlank()) name = null;
        if (arguments == null) arguments = "";
        this.index = index;
        this.id = id;
        this.name = name;
        this.arguments = arguments;
    }

}
