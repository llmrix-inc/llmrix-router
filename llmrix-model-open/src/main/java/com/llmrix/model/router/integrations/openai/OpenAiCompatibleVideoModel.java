package com.llmrix.model.router.integrations.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmrix.model.router.core.api.Usage;
import com.llmrix.model.router.core.api.video.VideoContent;
import com.llmrix.model.router.core.api.video.VideoLookupRequest;
import com.llmrix.model.router.core.api.video.VideoInput;
import com.llmrix.model.router.core.api.video.VideoModel;
import com.llmrix.model.router.core.api.video.VideoRemixRequest;
import com.llmrix.model.router.core.api.video.VideoRequest;
import com.llmrix.model.router.core.api.video.VideoResponse;

import java.time.Instant;
import java.util.Objects;

public final class OpenAiCompatibleVideoModel implements VideoModel {
    private final String modelName;
    private final OpenAiTransport transport;
    private final String routeModel;

    public OpenAiCompatibleVideoModel(String modelName, OpenAiTransport transport) {
        this(modelName, transport, null);
    }

    /**
     * Creates a video model that appends a Router route selector to lifecycle requests.
     * Provider integrations leave this unset because their model name is sent directly.
     */
    public OpenAiCompatibleVideoModel(String modelName, OpenAiTransport transport, String routeModel) {
        if (modelName == null || modelName.isBlank()) throw new IllegalArgumentException("modelName must not be blank");
        this.modelName = modelName;
        this.transport = Objects.requireNonNull(transport, "transport");
        this.routeModel = routeModel == null || routeModel.isBlank() ? null : routeModel;
    }

    @Override
    public VideoResponse create(VideoRequest request) {
        if (routeModel != null && request.inputReference() == null) {
            ObjectNode body = transport.mapper().createObjectNode()
                    .put("model", modelName).put("prompt", request.prompt());
            if (request.seconds() != null) body.put("seconds", request.seconds());
            if (request.size() != null) body.put("size", request.size());
            if (request.inputReferenceUrl() != null) {
                body.put("input_reference", request.inputReferenceUrl());
            }
            return parse(transport.postJson("videos", body, request.routingHints()));
        }
        MultipartBody body = new MultipartBody().text("model", modelName)
                .text("prompt", request.prompt()).text("seconds", request.seconds()).text("size", request.size());
        VideoInput input = request.inputReference();
        if (input != null) body.file("input_reference", input.filename(), input.mediaType(), input.data());
        else if (request.inputReferenceUrl() != null) body.text("input_reference", request.inputReferenceUrl());
        return parse(transport.readJson(transport.postMultipart("videos", body, request.routingHints()),
                "invalid OpenAI-compatible video response"));
    }

    @Override
    public VideoResponse retrieve(VideoLookupRequest request) {
        return parse(transport.readJson(transport.get(withRoute("videos/" + path(request.videoId())), request.routingHints()),
                "invalid OpenAI-compatible video response"));
    }

    @Override
    public VideoContent content(VideoLookupRequest request) {
        OpenAiTransport.Response response = transport.get(withRoute("videos/" + path(request.videoId()) + "/content"), request.routingHints());
        return new VideoContent(response.body(), response.mediaType(), modelName, Usage.UNKNOWN);
    }

    @Override
    public VideoResponse delete(VideoLookupRequest request) {
        return parse(transport.readJson(transport.delete(withRoute("videos/" + path(request.videoId())), request.routingHints()),
                "invalid OpenAI-compatible video response"));
    }

    @Override
    public VideoResponse remix(VideoRemixRequest request) {
        if (routeModel != null) {
            ObjectNode body = transport.mapper().createObjectNode().put("prompt", request.prompt());
            return parse(transport.postJson(
                    withRoute("videos/" + path(request.videoId()) + "/remix"), body, request.routingHints()));
        }
        // The Videos remix endpoint inherits the source video's model.
        MultipartBody body = new MultipartBody().text("prompt", request.prompt());
        return parse(transport.readJson(transport.postMultipart(
                withRoute("videos/" + path(request.videoId()) + "/remix"), body, request.routingHints()),
                "invalid OpenAI-compatible video response"));
    }

    private VideoResponse parse(JsonNode root) {
        if (!root.isObject() || (!root.hasNonNull("id") && !root.hasNonNull("status"))) {
            throw new com.llmrix.model.router.core.exception.ModelUnavailableException(
                    "invalid OpenAI-compatible video response: id or status is required");
        }
        JsonNode error = root.path("error");
        String errorMessage = error.isMissingNode() ? null : error.path("message").asText(null);
        JsonNode usage = root.path("usage");
        Usage tokenUsage = usage.isMissingNode() ? Usage.UNKNOWN : OpenAiUsageMapper.inputOutput(usage);
        return new VideoResponse(root.path("id").asText(null), root.path("object").asText("video"),
                root.path("status").asText(null), root.path("model").asText(modelName),
                longValue(root, "created_at"), longValue(root, "completed_at"), longValue(root, "expires_at"),
                root.has("progress") ? root.path("progress").asInt() : null, errorMessage,
                modelName, tokenUsage);
    }

    private static Long longValue(JsonNode root, String field) {
        return root.hasNonNull(field) ? root.path(field).asLong() : null;
    }

    private static String path(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private String withRoute(String value) {
        return routeModel == null ? value : value + "?model=" + path(routeModel);
    }
}
