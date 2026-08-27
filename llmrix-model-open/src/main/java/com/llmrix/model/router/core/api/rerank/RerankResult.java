package com.llmrix.model.router.core.api.rerank;

import lombok.Getter;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

@Getter
@EqualsAndHashCode
@Accessors(fluent = true)
public final class RerankResult {
    private final int index;
    private final double relevanceScore;
    private final String document;

    public RerankResult(int index, double relevanceScore, String document) {
        this.index = index;
        this.relevanceScore = relevanceScore;
        this.document = document;
    }
}
