package com.llmrix.model.router.integrations.evaluation;

import com.llmrix.model.router.core.api.chat.ChatRequest;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@EqualsAndHashCode
@Accessors(fluent = true)
public final class EvaluationSample {
    private final String id;
    private final ChatRequest request;

    public EvaluationSample(String id, ChatRequest request) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("sample id must not be blank");
        if (request == null) throw new IllegalArgumentException("sample request must not be null");
        this.id = id;
        this.request = request;
    }

}
