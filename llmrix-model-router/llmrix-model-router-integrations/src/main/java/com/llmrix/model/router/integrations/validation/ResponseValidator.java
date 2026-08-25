package com.llmrix.model.router.integrations.validation;

import com.llmrix.model.router.core.api.chat.ChatRequest;
import com.llmrix.model.router.core.api.chat.ChatResponse;

@FunctionalInterface
public interface ResponseValidator {
    void validate(ChatRequest request, ChatResponse response);
}
