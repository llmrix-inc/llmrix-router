package com.llmrix.model.router.core.api;

public record ToolCallDelta(int index, String id, String name, String arguments) {
    public ToolCallDelta {
        if (index < 0) throw new IllegalArgumentException("tool call index must be >= 0");
        if (id != null && id.isBlank()) id = null;
        if (name != null && name.isBlank()) name = null;
        if (arguments == null) arguments = "";
    }
}
