package com.llmrix.model.router.core.api.rerank;

import com.llmrix.model.router.core.api.ModelRequest;
import com.llmrix.model.router.core.routing.RoutingHints;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Objects;

@Getter
@Accessors(fluent = true)
public final class RerankRequest implements ModelRequest {
    private final String query;
    private final List<String> documents;
    private final Integer topN;
    private final boolean returnDocuments;
    private final RoutingHints routingHints;

    public RerankRequest(String query, List<String> documents, Integer topN,
                         Boolean returnDocuments, RoutingHints routingHints) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("rerank query must not be blank");
        if (documents == null || documents.isEmpty()) throw new IllegalArgumentException("rerank documents must not be empty");
        if (documents.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("rerank documents must not contain null");
        if (topN != null && topN < 1) throw new IllegalArgumentException("topN must be > 0");
        this.query = query;
        this.documents = List.copyOf(documents);
        this.topN = topN;
        this.returnDocuments = Boolean.TRUE.equals(returnDocuments);
        this.routingHints = routingHints == null ? RoutingHints.none() : routingHints;
    }

    public RerankRequest(String query, List<String> documents) {
        this(query, documents, null, false, null);
    }

    @Override
    public int estimatedInputTokens() {
        long estimate = query.length();
        estimate += documents.stream().mapToLong(String::length).sum();
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, (estimate + 3L) / 4L));
    }
}
