package com.llmrix.model.router.integrations.validation;

import com.llmrix.model.router.core.api.ChatRequest;
import com.llmrix.model.router.core.api.ChatResponse;

@FunctionalInterface
public interface ResponseValidator {
    void validate(ChatRequest request, ChatResponse response);
}
