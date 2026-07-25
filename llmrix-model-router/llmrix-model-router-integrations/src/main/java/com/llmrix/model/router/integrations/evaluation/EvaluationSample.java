package com.llmrix.model.router.integrations.evaluation;

import com.llmrix.model.router.core.api.ChatRequest;

public record EvaluationSample(String id, ChatRequest request) {
    public EvaluationSample {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("sample id must not be blank");
        if (request == null) throw new IllegalArgumentException("sample request must not be null");
    }
}
