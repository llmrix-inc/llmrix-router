package com.llmrix.model.router.core.api.embedding;

import com.llmrix.model.router.core.api.RoutedResponse;
import com.llmrix.model.router.core.api.Usage;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.List;

@Getter
@EqualsAndHashCode
@Accessors(fluent = true)
public final class EmbeddingResponse implements RoutedResponse<EmbeddingResponse> {
    private final List<EmbeddingVector> data;
    private final String modelId;
    private final Usage usage;

    public EmbeddingResponse(List<EmbeddingVector> data, String modelId, Usage usage) {
        this.data = data == null ? List.of() : List.copyOf(data);
        this.modelId = modelId;
        this.usage = usage == null ? Usage.UNKNOWN : usage;
    }

    @Override
    public EmbeddingResponse routedBy(String targetId) {
        return new EmbeddingResponse(data, targetId, usage);
    }
}
