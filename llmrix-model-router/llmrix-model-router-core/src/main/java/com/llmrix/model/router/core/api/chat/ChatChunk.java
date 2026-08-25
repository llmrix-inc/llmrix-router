package com.llmrix.model.router.core.api.chat;

import com.llmrix.model.router.core.api.Usage;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.List;

@Getter
@EqualsAndHashCode
@Accessors(fluent = true)
public final class ChatChunk {
    private final String text;
    private final boolean finished;
    private final Usage usage;
    private final List<ToolCallDelta> toolCallDeltas;
    private final String finishReason;

    public ChatChunk(String text, boolean finished, Usage usage) {
        this(text, finished, usage, List.of(), finished ? "stop" : null);
    }

    public ChatChunk(String text, boolean finished, Usage usage, List<ToolCallDelta> toolCallDeltas) {
        this(text, finished, usage, toolCallDeltas, finished ? "stop" : null);
    }

    public ChatChunk(String text, boolean finished, Usage usage,
                     List<ToolCallDelta> toolCallDeltas, String finishReason) {
        this.text = text == null ? "" : text;
        this.finished = finished;
        this.usage = usage == null ? Usage.UNKNOWN : usage;
        this.toolCallDeltas = toolCallDeltas == null ? List.of() : List.copyOf(toolCallDeltas);
        this.finishReason = finishReason;
    }

}
