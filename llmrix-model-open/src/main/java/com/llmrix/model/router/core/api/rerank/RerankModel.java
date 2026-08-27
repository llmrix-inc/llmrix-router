package com.llmrix.model.router.core.api.rerank;

public interface RerankModel {
    RerankResponse rerank(RerankRequest request);
}
