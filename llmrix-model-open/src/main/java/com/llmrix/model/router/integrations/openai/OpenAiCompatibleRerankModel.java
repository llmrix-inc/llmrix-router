package com.llmrix.model.router.integrations.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmrix.model.router.core.api.Usage;
import com.llmrix.model.router.core.api.rerank.RerankModel;
import com.llmrix.model.router.core.api.rerank.RerankRequest;
import com.llmrix.model.router.core.api.rerank.RerankResponse;
import com.llmrix.model.router.core.api.rerank.RerankResult;

import java.util.ArrayList;
import java.util.List;

/** Rerank adapter for providers exposing the Cohere/Jina-compatible /rerank API. */
public final class OpenAiCompatibleRerankModel implements RerankModel {
    private final String modelName;
    private final OpenAiTransport transport;

    public OpenAiCompatibleRerankModel(String modelName, OpenAiTransport transport) {
        if (modelName == null || modelName.isBlank()) throw new IllegalArgumentException("modelName must not be blank");
        this.modelName = modelName;
        this.transport = java.util.Objects.requireNonNull(transport, "transport");
    }

    @Override
    public RerankResponse rerank(RerankRequest request) {
        ObjectNode payload = transport.mapper().createObjectNode().put("model", modelName)
                .put("query", request.query());
        ArrayNode documents = payload.putArray("documents");
        request.documents().forEach(documents::add);
        if (request.topN() != null) payload.put("top_n", request.topN());
        if (request.returnDocuments()) payload.put("return_documents", true);
        JsonNode root = transport.postJson("rerank", payload, request.routingHints());
        List<RerankResult> results = new ArrayList<>();
        for (JsonNode item : root.path("results")) {
            JsonNode document = item.path("document");
            String text = document.isObject() ? document.path("text").asText(null)
                    : document.isTextual() ? document.asText() : null;
            results.add(new RerankResult(item.path("index").asInt(results.size()),
                    item.path("relevance_score").asDouble(item.path("score").asDouble()), text));
        }
        JsonNode usage = root.path("usage");
        return new RerankResponse(results, root.path("model").asText(modelName), OpenAiUsageMapper.inputOnly(usage));
    }
}
