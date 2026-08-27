package com.llmrix.model.router.core.api.rerank;

import com.llmrix.model.router.core.api.RoutedResponse;
import com.llmrix.model.router.core.api.Usage;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.List;

@Getter
@EqualsAndHashCode
@Accessors(fluent = true)
public final class RerankResponse implements RoutedResponse<RerankResponse> {
    private final List<RerankResult> results;
    private final String modelId;
    private final Usage usage;

    public RerankResponse(List<RerankResult> results, String modelId, Usage usage) {
        this.results = results == null ? List.of() : List.copyOf(results);
        this.modelId = modelId;
        this.usage = usage == null ? Usage.UNKNOWN : usage;
    }

    @Override
    public RerankResponse routedBy(String targetId) {
        return new RerankResponse(results, targetId, usage);
    }
}
