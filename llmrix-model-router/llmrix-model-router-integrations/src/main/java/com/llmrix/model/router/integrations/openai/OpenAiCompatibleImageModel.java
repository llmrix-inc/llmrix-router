package com.llmrix.model.router.integrations.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmrix.model.router.core.api.image.ImageData;
import com.llmrix.model.router.core.api.image.ImageEditRequest;
import com.llmrix.model.router.core.api.image.ImageInput;
import com.llmrix.model.router.core.api.image.ImageModel;
import com.llmrix.model.router.core.api.image.ImageRequest;
import com.llmrix.model.router.core.api.image.ImageResponse;
import com.llmrix.model.router.core.api.Usage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class OpenAiCompatibleImageModel implements ImageModel {
    private final String modelName;
    private final OpenAiTransport transport;

    public OpenAiCompatibleImageModel(String modelName, OpenAiTransport transport) {
        if (modelName == null || modelName.isBlank()) throw new IllegalArgumentException("modelName must not be blank");
        this.modelName = modelName;
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public ImageResponse generate(ImageRequest request) {
        ObjectNode payload = transport.mapper().createObjectNode().put("model", modelName);
        writeOptions(payload, request);
        return parse(transport.postJson("images/generations", payload));
    }

    @Override
    public ImageResponse edit(ImageEditRequest request) {
        MultipartBody body = new MultipartBody().text("model", modelName).text("prompt", request.prompt());
        for (ImageInput image : request.images()) {
            body.file("image", image.filename(), image.mediaType(), image.data());
        }
        if (request.mask() != null) {
            body.file("mask", request.mask().filename(), request.mask().mediaType(), request.mask().data());
        }
        writeOptions(body, request);
        OpenAiTransport.Response response = transport.postMultipart("images/edits", body);
        try {
            return parse(transport.mapper().readTree(response.body()));
        } catch (java.io.IOException error) {
            throw new com.llmrix.model.router.core.exception.ModelUnavailableException(
                    "invalid OpenAI-compatible image response", error);
        }
    }

    private void writeOptions(ObjectNode payload, ImageRequest request) {
        payload.put("prompt", request.prompt());
        if (request.count() != null) payload.put("n", request.count());
        if (request.size() != null) payload.put("size", request.size());
        if (request.quality() != null) payload.put("quality", request.quality());
        if (request.style() != null) payload.put("style", request.style());
        if (request.responseFormat() != null) payload.put("response_format", request.responseFormat());
        if (request.user() != null) payload.put("user", request.user());
        if (request.background() != null) payload.put("background", request.background());
        if (request.outputFormat() != null) payload.put("output_format", request.outputFormat());
        if (request.outputCompression() != null) payload.put("output_compression", request.outputCompression());
    }

    private void writeOptions(MultipartBody body, ImageRequest request) {
        body.text("n", request.count()).text("size", request.size()).text("quality", request.quality())
                .text("response_format", request.responseFormat()).text("user", request.user())
                .text("background", request.background()).text("output_format", request.outputFormat())
                .text("output_compression", request.outputCompression());
    }

    private ImageResponse parse(JsonNode root) {
        List<ImageData> images = new ArrayList<>();
        for (JsonNode item : root.path("data")) {
            images.add(new ImageData(item.path("url").asText(null),
                    item.path("b64_json").asText(null), item.path("revised_prompt").asText(null)));
        }
        JsonNode usage = root.path("usage");
        Usage tokenUsage = usage.isMissingNode() ? Usage.UNKNOWN : new Usage(
                usage.path("input_tokens").asLong(-1), usage.path("output_tokens").asLong(-1));
        return new ImageResponse(root.path("created").asLong(Instant.now().getEpochSecond()),
                images, root.path("model").asText(modelName), tokenUsage);
    }
}
