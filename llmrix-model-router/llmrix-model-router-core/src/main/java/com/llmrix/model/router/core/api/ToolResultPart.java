package com.llmrix.model.router.core.api;

import java.util.Objects;

public record ToolResultPart(String toolCallId, String result) implements ContentPart {
    public ToolResultPart {
        if (toolCallId == null || toolCallId.isBlank()) {
            throw new IllegalArgumentException("toolCallId must not be blank");
        }
        Objects.requireNonNull(result, "result");
    }
}
