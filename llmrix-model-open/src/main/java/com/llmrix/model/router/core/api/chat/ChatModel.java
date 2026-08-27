package com.llmrix.model.router.core.api.chat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

public interface ChatModel {
    ChatResponse chat(ChatRequest request);

    default ChatResponse chat(String userMessage) {
        return chat(ChatRequest.user(userMessage));
    }

    default CompletionStage<ChatResponse> chatAsync(ChatRequest request) {
        return CompletableFuture.supplyAsync(() -> chat(request));
    }

    default Flow.Publisher<ChatChunk> stream(ChatRequest request) {
        throw new UnsupportedOperationException("streaming is not supported by this model");
    }

    /** Whether this adapter has a native streaming protocol. */
    default boolean supportsStreaming() { return false; }

    /** Whether this adapter accepts tool definitions and tool results. */
    default boolean supportsTools() { return false; }

    /** Whether this adapter supports structured response formats. */
    default boolean supportsStructuredOutput() { return false; }

    /** Whether prompt cache hints can be sent to the provider. */
    default boolean supportsPromptCache() { return false; }
}
