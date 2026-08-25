package com.llmrix.model.router.core.api.chat;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.Objects;

@Getter
@EqualsAndHashCode
@Accessors(fluent = true)
public final class ToolResultPart implements ContentPart {
    private final String toolCallId;
    private final String result;

    public ToolResultPart(String toolCallId, String result) {
        if (toolCallId == null || toolCallId.isBlank()) {
            throw new IllegalArgumentException("toolCallId must not be blank");
        }
        this.toolCallId = toolCallId;
        this.result = Objects.requireNonNull(result, "result");
    }

}
