package com.llmrix.model.router.integrations.validation;

import com.llmrix.model.router.core.api.chat.ChatRequest;
import com.llmrix.model.router.core.api.chat.ChatResponse;

import java.util.List;
import java.util.Objects;

public final class CompositeResponseValidator implements ResponseValidator {
    private final List<ResponseValidator> validators;

    public CompositeResponseValidator(List<? extends ResponseValidator> validators) {
        Objects.requireNonNull(validators, "validators");
        this.validators = List.copyOf(validators);
        if (this.validators.isEmpty()) throw new IllegalArgumentException("at least one validator is required");
    }

    public CompositeResponseValidator(ResponseValidator... validators) {
        this(List.of(validators));
    }

    @Override
    public void validate(ChatRequest request, ChatResponse response) {
        validators.forEach(validator -> validator.validate(request, response));
    }
}
