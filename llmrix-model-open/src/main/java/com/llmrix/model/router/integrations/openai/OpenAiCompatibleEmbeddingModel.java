package com.llmrix.model.router.integrations.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmrix.model.router.core.api.embedding.EmbeddingInput;
import com.llmrix.model.router.core.api.embedding.EmbeddingModel;
import com.llmrix.model.router.core.api.embedding.EmbeddingRequest;
import com.llmrix.model.router.core.api.embedding.EmbeddingResponse;
import com.llmrix.model.router.core.api.embedding.EmbeddingVector;
import com.llmrix.model.router.core.api.Usage;

import java.util.ArrayList;
import java.util.List;

public final class OpenAiCompatibleEmbeddingModel implements EmbeddingModel {
    private final String modelName;
    private final OpenAiTransport transport;

    public OpenAiCompatibleEmbeddingModel(String modelName, OpenAiTransport transport) {
        if (modelName == null || modelName.isBlank()) throw new IllegalArgumentException("modelName must not be blank");
        this.modelName = modelName;
        this.transport = java.util.Objects.requireNonNull(transport, "transport");
    }

    @Override
    public EmbeddingResponse embed(EmbeddingRequest request) {
        ObjectNode payload = transport.mapper().createObjectNode().put("model", modelName)
                .put("encoding_format", request.encodingFormat().name().toLowerCase());
        if (request.dimensions() != null) payload.put("dimensions", request.dimensions());
        if (request.user() != null) payload.put("user", request.user());
        writeInputs(payload, request.inputs());
        JsonNode root = transport.postJson("embeddings", payload, request.routingHints());
        if (!root.path("data").isArray()) {
            throw new com.llmrix.model.router.core.exception.ModelUnavailableException(
                    "invalid OpenAI-compatible embedding response: data must be an array");
        }
        List<EmbeddingVector> vectors = new ArrayList<>();
        for (JsonNode item : root.path("data")) {
            int index = item.path("index").asInt(vectors.size());
            JsonNode embedding = item.path("embedding");
            if (embedding.isTextual()) {
                vectors.add(EmbeddingVector.base64(index, embedding.asText()));
            } else {
                List<Double> values = new ArrayList<>();
                embedding.forEach(value -> values.add(value.asDouble()));
                vectors.add(EmbeddingVector.floats(index, values));
            }
        }
        JsonNode usage = root.path("usage");
        return new EmbeddingResponse(vectors, root.path("model").asText(modelName), OpenAiUsageMapper.inputOnly(usage));
    }

    private void writeInputs(ObjectNode payload, List<EmbeddingInput> inputs) {
        if (inputs.size() == 1) {
            EmbeddingInput input = inputs.get(0);
            if (input.tokenized()) payload.set("input", transport.mapper().valueToTree(input.tokens()));
            else payload.put("input", input.text());
            return;
        }
        ArrayNode array = payload.putArray("input");
        for (EmbeddingInput input : inputs) {
            if (input.tokenized()) array.add(transport.mapper().valueToTree(input.tokens()));
            else array.add(input.text());
        }
    }
}
