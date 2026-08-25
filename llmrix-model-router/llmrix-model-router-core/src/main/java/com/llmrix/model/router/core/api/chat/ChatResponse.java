package com.llmrix.model.router.core.api.chat;

import com.llmrix.model.router.core.api.RoutedResponse;
import com.llmrix.model.router.core.api.Usage;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Getter
@EqualsAndHashCode
@Accessors(fluent = true)
public final class ChatResponse implements RoutedResponse<ChatResponse> {
    private final String text;
    private final String modelId;
    private final Usage usage;
    private final Map<String, Object> metadata;
    private final List<ToolCallPart> toolCalls;
    private final String finishReason;

    public ChatResponse(String text, String modelId, Usage usage, Map<String, Object> metadata,
                        List<ToolCallPart> toolCalls, String finishReason) {
        this.text = Objects.requireNonNull(text, "text");
        this.modelId = modelId;
        this.usage = usage == null ? Usage.UNKNOWN : usage;
        this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        this.toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        this.finishReason = finishReason;
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

    @Override
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
