package com.llmrix.model.router.core.api;

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
}
