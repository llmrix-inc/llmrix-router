package com.llmrix.model.router.core.api.embedding;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.List;

@Getter
@EqualsAndHashCode
@Accessors(fluent = true)
public final class EmbeddingVector {
    private final int index;
    private final List<Double> values;
    private final String base64;

    public EmbeddingVector(int index, List<Double> values, String base64) {
        if (index < 0) throw new IllegalArgumentException("index must be >= 0");
        if ((values == null) == (base64 == null)) {
            throw new IllegalArgumentException("exactly one embedding representation is required");
        }
        this.index = index;
        this.values = values == null ? null : List.copyOf(values);
        this.base64 = base64;
    }

    public static EmbeddingVector floats(int index, List<Double> values) {
        return new EmbeddingVector(index, values, null);
    }

    public static EmbeddingVector base64(int index, String value) {
        return new EmbeddingVector(index, null, value);
    }
}
