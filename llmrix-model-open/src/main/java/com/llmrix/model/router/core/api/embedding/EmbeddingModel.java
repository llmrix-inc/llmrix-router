package com.llmrix.model.router.core.api.embedding;

public interface EmbeddingModel {
    EmbeddingResponse embed(EmbeddingRequest request);
}
