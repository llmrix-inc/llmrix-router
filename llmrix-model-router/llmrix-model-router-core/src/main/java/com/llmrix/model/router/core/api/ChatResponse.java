package com.llmrix.model.router.core.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ChatResponse(String text, String modelId, Usage usage, Map<String, Object> metadata,
                           List<ToolCallPart> toolCalls, String finishReason) {
    public ChatResponse {
        Objects.requireNonNull(text, "text");
        usage = usage == null ? Usage.UNKNOWN : usage;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public ChatResponse(String text, String modelId, Usage usage, Map<String, Object> metadata) {
        this(text, modelId, usage, metadata, List.of(), "stop");
    }

    public ChatResponse(String text, String modelId, Usage usage, Map<String, Object> metadata,
                        List<ToolCallPart> toolCalls) {
        this(text, modelId, usage, metadata, toolCalls, toolCalls == null || toolCalls.isEmpty() ? "stop" : "tool_calls");
    }

    public static ChatResponse of(String text) {
        return new ChatResponse(text, null, Usage.UNKNOWN, Map.of(), List.of(), "stop");
    }

    public ChatResponse routedBy(String candidateId) {
        return new ChatResponse(text, candidateId, usage, metadata, toolCalls, finishReason);
    }

    public Message assistantMessage() {
        if (toolCalls.isEmpty()) return Message.assistant(text);
        java.util.ArrayList<ContentPart> parts = new java.util.ArrayList<>();
        if (!text.isEmpty()) parts.add(new TextPart(text));
        parts.addAll(toolCalls);
        return new Message("assistant", parts);
    }
}
