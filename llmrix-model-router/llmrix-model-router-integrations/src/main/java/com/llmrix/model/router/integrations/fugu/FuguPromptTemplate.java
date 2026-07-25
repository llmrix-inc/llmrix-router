package com.llmrix.model.router.integrations.fugu;

import com.llmrix.model.router.core.api.ChatRequest;

@FunctionalInterface
public interface FuguPromptTemplate {
    ChatRequest create(ChatRequest original, FuguRole role, String latestAnswer, String latestSuggestion);
}
