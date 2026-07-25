package com.llmrix.model.router.core.api;

import java.util.List;

public record ChatChunk(String text, boolean finished, Usage usage,
                        List<ToolCallDelta> toolCallDeltas, String finishReason) {
    public ChatChunk(String text, boolean finished, Usage usage) {
        this(text, finished, usage, List.of(), finished ? "stop" : null);
    }
    public ChatChunk(String text, boolean finished, Usage usage, List<ToolCallDelta> toolCallDeltas) {
        this(text, finished, usage, toolCallDeltas, finished ? "stop" : null);
    }
    public ChatChunk {
        text = text == null ? "" : text;
        usage = usage == null ? Usage.UNKNOWN : usage;
        toolCallDeltas = toolCallDeltas == null ? List.of() : List.copyOf(toolCallDeltas);
    }
}
