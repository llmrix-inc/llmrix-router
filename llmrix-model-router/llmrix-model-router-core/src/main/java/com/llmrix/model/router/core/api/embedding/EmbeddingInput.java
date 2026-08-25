package com.llmrix.model.router.core.api.embedding;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Objects;

@Getter
@EqualsAndHashCode
@Accessors(fluent = true)
public final class EmbeddingInput {
    private final String text;
    private final List<Integer> tokens;

    private EmbeddingInput(String text, List<Integer> tokens) {
        this.text = text;
        this.tokens = tokens == null ? null : List.copyOf(tokens);
    }

    public static EmbeddingInput text(String value) {
        if (value == null) throw new IllegalArgumentException("embedding text must not be null");
        return new EmbeddingInput(value, null);
    }

    public static EmbeddingInput tokens(List<Integer> value) {
        Objects.requireNonNull(value, "tokens");
        if (value.isEmpty() || value.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("embedding tokens must not be empty or contain null");
        }
        return new EmbeddingInput(null, value);
    }

    public boolean tokenized() {
        return tokens != null;
    }
}
