package com.llmrix.model.router.integrations.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmrix.model.router.core.api.Usage;
import com.llmrix.model.router.core.api.embedding.EmbeddingInput;
import com.llmrix.model.router.core.api.embedding.EmbeddingModel;
import com.llmrix.model.router.core.api.embedding.EmbeddingRequest;
import com.llmrix.model.router.core.api.embedding.EmbeddingResponse;
import com.llmrix.model.router.core.api.embedding.EmbeddingVector;
import com.llmrix.model.router.integrations.JsonTransport;

import java.util.ArrayList;
import java.util.List;

/** Native Ollama /api/embed adapter. */
public final class OllamaEmbeddingModel implements EmbeddingModel {
    private final String modelName;
    private final JsonTransport transport;

    public OllamaEmbeddingModel(String modelName, JsonTransport transport) {
        this.modelName = modelName;
        this.transport = java.util.Objects.requireNonNull(transport, "transport");
    }

    @Override public EmbeddingResponse embed(EmbeddingRequest request) {
        ObjectNode payload = transport.mapper().createObjectNode().put("model", modelName);
        ArrayNode input = payload.putArray("input");
        for (EmbeddingInput value : request.inputs()) {
            if (value.tokenized()) input.add(transport.mapper().valueToTree(value.tokens()));
            else input.add(value.text());
        }
        JsonNode root = transport.postJson("api/embed", payload);
        List<EmbeddingVector> vectors = new ArrayList<>();
        for (JsonNode embedding : root.path("embeddings")) {
            List<Double> values = new ArrayList<>();
            embedding.forEach(value -> values.add(value.asDouble()));
            vectors.add(EmbeddingVector.floats(vectors.size(), values));
        }
        return new EmbeddingResponse(vectors, root.path("model").asText(modelName),
                new Usage(root.path("prompt_eval_count").asLong(-1), 0));
    }
}
