package com.llmrix.model.router.integrations.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmrix.model.router.core.api.chat.ChatModel;
import com.llmrix.model.router.core.api.chat.ChatRequest;
import com.llmrix.model.router.core.api.chat.ChatResponse;
import com.llmrix.model.router.core.api.chat.Message;
import com.llmrix.model.router.core.api.Usage;
import com.llmrix.model.router.core.api.chat.TextPart;
import com.llmrix.model.router.core.api.chat.ImagePart;
import com.llmrix.model.router.core.api.chat.AudioPart;
import com.llmrix.model.router.core.api.chat.VideoPart;
import com.llmrix.model.router.core.api.chat.FilePart;
import com.llmrix.model.router.core.api.chat.ToolCallPart;
import com.llmrix.model.router.core.api.chat.ToolChoice;
import com.llmrix.model.router.core.api.chat.ToolResultPart;
import com.llmrix.model.router.core.api.chat.ToolCallDelta;
import com.llmrix.model.router.core.api.chat.ResponseFormat;
import com.llmrix.model.router.core.exception.InvalidRequestException;
import com.llmrix.model.router.core.exception.ModelTimeoutException;
import com.llmrix.model.router.core.exception.ModelUnavailableException;
import com.llmrix.model.router.core.spi.auth.RequestAuthenticator;
import com.llmrix.model.router.integrations.RoutingHintsHttpCodec;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.ArrayList;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

public final class OpenAiCompatibleChatModel implements ChatModel {
    private static final System.Logger LOGGER = System.getLogger(OpenAiCompatibleChatModel.class.getName());
    public enum Api {CHAT_COMPLETIONS, RESPONSES}

    private final String modelName;
    private final URI completionsUri;
    private final RequestAuthenticator authenticator;
    private final Duration timeout;
    private final Double temperature;
    private final Integer maxTokens;
    private final Map<String, String> headers;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<String, Object> extensions;
    private final Api api;
    private final boolean forwardRoutingHints;
    private static final Set<String> RESERVED_FIELDS = Set.of(
            "model", "messages", "stream", "stream_options", "temperature", "top_p", "max_tokens",
            "stop", "tools", "tool_choice", "response_format", "seed", "n", "logprobs", "user",
            "input", "max_output_tokens", "prompt_cache_key", "prompt_cache_retention");

    private OpenAiCompatibleChatModel(Builder builder) {
        this.modelName = requireText(builder.modelName, "modelName");
        this.api = builder.api;
        this.completionsUri = endpointUri(requireText(builder.baseUrl, "baseUrl"), api);
        this.authenticator = builder.authenticator != null
                ? builder.authenticator
                : bearerToken(builder.apiKey);
        this.timeout = builder.timeout;
        this.temperature = builder.temperature;
        this.maxTokens = builder.maxTokens;
        this.headers = sanitizeHeaders(builder.headers, builder.forwardRoutingHints);
        this.httpClient = builder.httpClient == null
                ? HttpClient.newBuilder().connectTimeout(builder.connectTimeout).build()
                : builder.httpClient;
        this.objectMapper = builder.objectMapper == null ? new ObjectMapper() : builder.objectMapper;
        this.extensions = Map.copyOf(builder.extensions);
        this.forwardRoutingHints = builder.forwardRoutingHints;
        extensions.keySet().forEach(key -> {
            if (key == null || key.isBlank()) throw new IllegalArgumentException("extension key must not be blank");
            if (RESERVED_FIELDS.contains(key))
                throw new IllegalArgumentException("extension cannot override standard field: " + key);
        });
    }

    public static Builder builder() {
        return new Builder();
    }

    public Api api() {
        return api;
    }

    @Override public boolean supportsStreaming() { return true; }
    @Override public boolean supportsTools() { return true; }
    @Override public boolean supportsStructuredOutput() { return true; }
    @Override public boolean supportsPromptCache() { return true; }

    @Override
    public ChatResponse chat(ChatRequest request) {
        ObjectNode payload = requestPayload(request, false);

        try {
            HttpResponse<String> response = httpClient.send(
                    requestBuilder(payload, request.routingHints()).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw OpenAiErrorMapper.map(response.statusCode(), response.body(), objectMapper);
            }
            return parse(response.body());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new ModelTimeoutException("OpenAI-compatible request timed out", e);
        } catch (IOException e) {
            throw new ModelUnavailableException("OpenAI-compatible request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelUnavailableException("OpenAI-compatible request interrupted", e);
        }
    }

    @Override
    public CompletionStage<ChatResponse> chatAsync(ChatRequest request) {
        ObjectNode payload = requestPayload(request, false);
        CompletableFuture<HttpResponse<String>> transport = httpClient.sendAsync(
                requestBuilder(payload, request.routingHints()).build(), HttpResponse.BodyHandlers.ofString());
        CompletableFuture<ChatResponse> result = new CompletableFuture<>();
        transport.whenComplete((response, failure) -> {
            if (failure != null) {
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                        ? failure.getCause() : failure;
                if (cause instanceof java.net.http.HttpTimeoutException) {
                    result.completeExceptionally(new ModelTimeoutException("OpenAI-compatible request timed out", cause));
                } else {
                    result.completeExceptionally(new ModelUnavailableException("OpenAI-compatible async request failed", cause));
                }
                return;
            }
            try {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    result.completeExceptionally(OpenAiErrorMapper.map(
                            response.statusCode(), response.body(), objectMapper));
                } else {
                    result.complete(parse(response.body()));
                }
            } catch (RuntimeException error) {
                result.completeExceptionally(error);
            }
        });
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled()) transport.cancel(true);
        });
        return result;
    }

    @Override
    public Flow.Publisher<com.llmrix.model.router.core.api.chat.ChatChunk> stream(ChatRequest request) {
        ObjectNode payload = requestPayload(request, true);
        return subscriber -> {
            SubmissionPublisher<com.llmrix.model.router.core.api.chat.ChatChunk> publisher = new SubmissionPublisher<>();
            HttpStreamControl control = new HttpStreamControl();
            publisher.subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscriber.onSubscribe(new Flow.Subscription() {
                        @Override
                        public void request(long n) {
                            subscription.request(n);
                        }

                        @Override
                        public void cancel() {
                            subscription.cancel();
                            control.cancel();
                            publisher.close();
                        }
                    });
                }

                @Override
                public void onNext(com.llmrix.model.router.core.api.chat.ChatChunk item) {
                    subscriber.onNext(item);
                }

                @Override
                public void onError(Throwable throwable) {
                    subscriber.onError(throwable);
                }

                @Override
                public void onComplete() {
                    subscriber.onComplete();
                }
            });
            control.future(startStream(payload, request.routingHints(), publisher, control));
        };
    }

    private CompletableFuture<?> startStream(
            ObjectNode payload,
            com.llmrix.model.router.core.routing.RoutingHints hints,
            SubmissionPublisher<com.llmrix.model.router.core.api.chat.ChatChunk> publisher,
            HttpStreamControl control) {
        HttpRequest.Builder httpRequest = requestBuilder(payload, hints);
        CompletableFuture<HttpResponse<Stream<String>>> request =
                httpClient.sendAsync(httpRequest.build(), HttpResponse.BodyHandlers.ofLines());
        request
                .thenAccept(response -> {
                    control.body(response.body());
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        try (var lines = response.body()) {
                            if (control.terminate()) {
                                String body = lines.limit(20).reduce("", (left, right) -> left + right);
                                publisher.closeExceptionally(OpenAiErrorMapper.map(
                                        response.statusCode(), body, objectMapper));
                            }
                        }
                        return;
                    }
                    try (var lines = response.body()) {
                        SseEventDecoder decoder = new SseEventDecoder();
                        AtomicReference<Usage> streamUsage = new AtomicReference<>(Usage.UNKNOWN);
                        AtomicReference<String> finishReason = new AtomicReference<>();
                        boolean includeUsage = payload.path("stream_options").path("include_usage").asBoolean(true);
                        var iterator = lines.iterator();
                        while (!control.terminated() && iterator.hasNext()) {
                            String line = iterator.next();
                            var event = decoder.accept(line);
                            if (event.isPresent() && handleStreamEvent(
                                    event.get(), streamUsage, finishReason, publisher, control, includeUsage)) {
                                return;
                            }
                        }
                        var finalEvent = decoder.finish();
                        if (finalEvent.isPresent() && handleStreamEvent(
                                finalEvent.get(), streamUsage, finishReason, publisher, control, includeUsage)) return;
                        if (control.terminate()) publisher.close();
                    }
                })
                .exceptionally(error -> {
                    if (control.terminate()) {
                        publisher.closeExceptionally(new ModelUnavailableException("stream request failed", error));
                    }
                    return null;
                });
        return request;
    }

    private boolean handleStreamEvent(
            String data,
            AtomicReference<Usage> streamUsage,
            AtomicReference<String> finishReason,
            SubmissionPublisher<com.llmrix.model.router.core.api.chat.ChatChunk> publisher,
            HttpStreamControl control,
            boolean includeUsage) {
        if ("[DONE]".equals(data.strip())) {
            publisher.submit(new com.llmrix.model.router.core.api.chat.ChatChunk("", true,
                    includeUsage ? streamUsage.get() : Usage.UNKNOWN, java.util.List.of(),
                    finishReason.get() == null ? "stop" : finishReason.get()));
            if (control.terminate()) publisher.close();
            return true;
        }
        try {
            JsonNode root = objectMapper.readTree(data);
            if (api == Api.RESPONSES) {
                return handleResponsesStreamEvent(
                        root, finishReason, publisher, control, includeUsage);
            }
            JsonNode error = root.path("error");
            if (error.isObject()) {
                if (control.terminate()) {
                    publisher.closeExceptionally(OpenAiErrorMapper.map(502, root.toString(), objectMapper));
                }
                return true;
            }
            JsonNode usage = root.path("usage");
            if (includeUsage && !usage.isMissingNode() && !usage.isNull()) {
                streamUsage.set(OpenAiUsageMapper.chat(usage));
            }
            JsonNode reason = root.path("choices").path(0).path("finish_reason");
            if (!reason.isMissingNode() && !reason.isNull() && !reason.asText().isBlank()) {
                finishReason.set(reason.asText());
            }
            JsonNode delta = root.path("choices").path(0).path("delta").path("content");
            String text = delta.isMissingNode() || delta.isNull() ? "" : delta.asText();
            java.util.ArrayList<ToolCallDelta> toolDeltas = new java.util.ArrayList<>();
            for (JsonNode call : root.path("choices").path(0).path("delta").path("tool_calls")) {
                JsonNode function = call.path("function");
                toolDeltas.add(new ToolCallDelta(call.path("index").asInt(0),
                        call.path("id").asText(null), function.path("name").asText(null),
                        function.path("arguments").asText("")));
            }
            if (!text.isEmpty() || !toolDeltas.isEmpty()) {
                publisher.submit(new com.llmrix.model.router.core.api.chat.ChatChunk(text, false, Usage.UNKNOWN, toolDeltas));
            }
            return false;
        } catch (IOException error) {
            if (control.terminate()) {
                publisher.closeExceptionally(new ModelUnavailableException("invalid streaming response", error));
            }
            return true;
        }
    }

    private boolean handleResponsesStreamEvent(
            JsonNode root,
            AtomicReference<String> finishReason,
            SubmissionPublisher<com.llmrix.model.router.core.api.chat.ChatChunk> publisher,
            HttpStreamControl control,
            boolean includeUsage) {
        String type = root.path("type").asText();
        if ("response.output_text.delta".equals(type)) {
            publisher.submit(new com.llmrix.model.router.core.api.chat.ChatChunk(
                    root.path("delta").asText(""), false, Usage.UNKNOWN));
            return false;
        }
        if ("response.output_item.added".equals(type)) {
            JsonNode item = root.path("item");
            if ("function_call".equals(item.path("type").asText())) {
                finishReason.set("tool_calls");
                publisher.submit(new com.llmrix.model.router.core.api.chat.ChatChunk("", false, Usage.UNKNOWN,
                        java.util.List.of(new ToolCallDelta(root.path("output_index").asInt(0),
                                item.path("call_id").asText(item.path("id").asText(null)),
                                item.path("name").asText(null), item.path("arguments").asText("")))));
            }
            return false;
        }
        if ("response.function_call_arguments.delta".equals(type)) {
            finishReason.set("tool_calls");
            publisher.submit(new com.llmrix.model.router.core.api.chat.ChatChunk("", false, Usage.UNKNOWN,
                    java.util.List.of(new ToolCallDelta(root.path("output_index").asInt(0),
                            null, null, root.path("delta").asText("")))));
            return false;
        }
        if ("response.completed".equals(type)) {
            JsonNode usage = root.path("response").path("usage");
            Usage tokenUsage = includeUsage && !usage.isMissingNode()
                    ? OpenAiUsageMapper.responses(usage) : Usage.UNKNOWN;
            publisher.submit(new com.llmrix.model.router.core.api.chat.ChatChunk("", true, tokenUsage,
                    java.util.List.of(), finishReason.get() == null ? "stop" : finishReason.get()));
            if (control.terminate()) publisher.close();
            return true;
        }
        if ("response.failed".equals(type) || "error".equals(type)) {
            if (control.terminate()) publisher.closeExceptionally(new ModelUnavailableException(
                    "Responses API stream failed: " + truncate(root.toString())));
            return true;
        }
        return false;
    }

    private static final class HttpStreamControl {
        private final AtomicBoolean terminated = new AtomicBoolean();
        private final AtomicReference<CompletableFuture<?>> future = new AtomicReference<>();
        private final AtomicReference<Stream<String>> body = new AtomicReference<>();

        boolean terminated() {
            return terminated.get();
        }

        boolean terminate() {
            return terminated.compareAndSet(false, true);
        }

        void future(CompletableFuture<?> value) {
            future.set(value);
            if (terminated.get()) value.cancel(true);
        }

        void body(Stream<String> value) {
            body.set(value);
            if (terminated.get()) value.close();
        }

        void cancel() {
            terminated.set(true);
            CompletableFuture<?> active = future.getAndSet(null);
            if (active != null) active.cancel(true);
            Stream<String> lines = body.getAndSet(null);
            if (lines != null) lines.close();
        }
    }

    private ObjectNode requestPayload(ChatRequest request, boolean stream) {
        if (api == Api.RESPONSES) return responsesRequestPayload(request, stream);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", modelName);
        payload.put("stream", stream);
        if (stream) payload.putObject("stream_options").put("include_usage", request.streamOptions().includeUsage());
        ArrayNode messages = payload.putArray("messages");
        for (Message message : request.messages()) {
            ObjectNode node = messages.addObject();
            node.put("role", message.role());
            var toolResult = message.contents().stream()
                    .filter(ToolResultPart.class::isInstance).map(ToolResultPart.class::cast).findFirst();
            var toolCalls = message.contents().stream()
                    .filter(ToolCallPart.class::isInstance).map(ToolCallPart.class::cast).toList();
            if (toolResult.isPresent()) {
                if (message.contents().size() != 1 || !"tool".equals(message.role())) {
                    throw new InvalidRequestException("tool result must be the only content of a tool message");
                }
                node.put("tool_call_id", toolResult.get().toolCallId());
                node.put("content", toolResult.get().result());
            } else if (!toolCalls.isEmpty()) {
                if (!"assistant".equals(message.role())) {
                    throw new InvalidRequestException("tool calls require an assistant message");
                }
                node.put("content", message.content().isEmpty() ? null : message.content());
                ArrayNode calls = node.putArray("tool_calls");
                for (ToolCallPart call : toolCalls) {
                    ObjectNode callNode = calls.addObject();
                    callNode.put("id", call.id());
                    callNode.put("type", "function");
                    ObjectNode function = callNode.putObject("function");
                    function.put("name", call.name());
                    function.put("arguments", call.arguments());
                }
            } else if (message.textOnly()) {
                node.put("content", message.content());
            } else {
                ArrayNode content = node.putArray("content");
                for (var part : message.contents()) {
                    ObjectNode partNode = content.addObject();
                    if (part instanceof TextPart text) {
                        partNode.put("type", "text");
                        partNode.put("text", text.text());
                    } else if (part instanceof ImagePart image) {
                        partNode.put("type", "image_url");
                        ObjectNode imageUrl = partNode.putObject("image_url");
                        imageUrl.put("url", image.url());
                        if (image.detail() != null) imageUrl.put("detail", image.detail());
                    } else if (part instanceof AudioPart audio) {
                        partNode.put("type", "input_audio");
                        ObjectNode inputAudio = partNode.putObject("input_audio");
                        inputAudio.put("data", audio.data());
                        inputAudio.put("format", audio.format());
                    } else if (part instanceof VideoPart video) {
                        partNode.put("type", "video_url");
                        partNode.putObject("video_url").put("url", video.url());
                    } else if (part instanceof FilePart file) {
                        partNode.put("type", "file");
                        ObjectNode fileNode = partNode.putObject("file");
                        if (file.filename() != null) fileNode.put("filename", file.filename());
                        if (file.fileId() != null) fileNode.put("file_id", file.fileId());
                        else if (file.url().startsWith("data:")) fileNode.put("file_data", file.url());
                        else fileNode.put("file_url", file.url());
                    }
                }
            }
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = payload.putArray("tools");
            request.tools().forEach(tool -> {
                ObjectNode function = tools.addObject().put("type", "function").putObject("function");
                function.put("name", tool.name());
                if (tool.description() != null) function.put("description", tool.description());
                function.set("parameters", objectMapper.valueToTree(tool.parameters()));
                if (tool.strict()) function.put("strict", true);
            });
        }
        if (request.toolChoice() != null) writeToolChoice(payload, request.toolChoice());
        if (request.responseFormat() != null) writeResponseFormat(payload, request.responseFormat());
        Double requestTemperature = request.generationOptions().temperature();
        if (requestTemperature != null) payload.put("temperature", requestTemperature);
        else if (temperature != null) payload.put("temperature", temperature);
        if (request.generationOptions().topP() != null) payload.put("top_p", request.generationOptions().topP());
        Integer requestMaxTokens = request.generationOptions().maxOutputTokens();
        if (requestMaxTokens != null) payload.put("max_tokens", requestMaxTokens);
        else if (maxTokens != null) payload.put("max_tokens", maxTokens);
        if (!request.generationOptions().stop().isEmpty()) {
            ArrayNode stops = payload.putArray("stop");
            request.generationOptions().stop().forEach(stops::add);
        }
        if (request.generationOptions().seed() != null) payload.put("seed", request.generationOptions().seed());
        if (request.generationOptions().candidateCount() != null)
            payload.put("n", request.generationOptions().candidateCount());
        if (request.generationOptions().logprobs() != null)
            payload.put("logprobs", request.generationOptions().logprobs());
        if (request.generationOptions().user() != null) payload.put("user", request.generationOptions().user());
        writePromptCache(payload, request);
        extensions.forEach((key, value) -> payload.set(key, objectMapper.valueToTree(value)));
        return payload;
    }

    private ObjectNode responsesRequestPayload(ChatRequest request, boolean stream) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", modelName);
        payload.put("stream", stream);
        ArrayNode input = payload.putArray("input");
        for (Message message : request.messages()) {
            var resultPart = message.contents().stream().filter(ToolResultPart.class::isInstance)
                    .map(ToolResultPart.class::cast).findFirst();
            if (resultPart.isPresent()) {
                ToolResultPart result = resultPart.get();
                input.addObject().put("type", "function_call_output")
                        .put("call_id", result.toolCallId()).put("output", result.result());
                continue;
            }
            var calls = message.contents().stream().filter(ToolCallPart.class::isInstance)
                    .map(ToolCallPart.class::cast).toList();
            if (!calls.isEmpty()) {
                calls.forEach(call -> input.addObject().put("type", "function_call")
                        .put("call_id", call.id()).put("name", call.name()).put("arguments", call.arguments()));
                continue;
            }
            ObjectNode item = input.addObject().put("role", message.role());
            if (message.textOnly()) {
                item.put("content", message.content());
            } else {
                ArrayNode content = item.putArray("content");
                for (var part : message.contents()) {
                    if (part instanceof TextPart text) {
                        content.addObject().put("type", "input_text").put("text", text.text());
                    } else if (part instanceof ImagePart image) {
                        ObjectNode imageNode = content.addObject().put("type", "input_image")
                                .put("image_url", image.url());
                        if (image.detail() != null) imageNode.put("detail", image.detail());
                    } else if (part instanceof VideoPart) {
                        throw new InvalidRequestException(
                                "video input is supported by chat completions, not the Responses API");
                    } else if (part instanceof FilePart file) {
                        ObjectNode fileNode = content.addObject().put("type", "input_file");
                        if (file.filename() != null) fileNode.put("filename", file.filename());
                        if (file.fileId() != null) fileNode.put("file_id", file.fileId());
                        else if (file.url().startsWith("data:")) fileNode.put("file_data", file.url());
                        else fileNode.put("file_url", file.url());
                    } else {
                        throw new InvalidRequestException(
                                "unsupported Responses input content: " + part.getClass().getSimpleName());
                    }
                }
            }
        }
        var options = request.generationOptions();
        if (options.temperature() != null) payload.put("temperature", options.temperature());
        if (options.topP() != null) payload.put("top_p", options.topP());
        if (options.maxOutputTokens() != null) payload.put("max_output_tokens", options.maxOutputTokens());
        if (options.user() != null) payload.put("user", options.user());
        writePromptCache(payload, request);
        if (!request.tools().isEmpty()) {
            ArrayNode tools = payload.putArray("tools");
            request.tools().forEach(tool -> {
                ObjectNode node = tools.addObject().put("type", "function").put("name", tool.name());
                if (tool.description() != null) node.put("description", tool.description());
                node.set("parameters", objectMapper.valueToTree(tool.parameters()));
                if (tool.strict()) node.put("strict", true);
            });
        }
        if (request.toolChoice() != null) {
            switch (request.toolChoice().mode()) {
                case AUTO -> payload.put("tool_choice", "auto");
                case NONE -> payload.put("tool_choice", "none");
                case REQUIRED -> payload.put("tool_choice", "required");
                case NAMED -> payload.putObject("tool_choice").put("type", "function")
                        .put("name", request.toolChoice().name());
            }
        }
        extensions.forEach((key, value) -> payload.set(key, objectMapper.valueToTree(value)));
        return payload;
    }

    private void writePromptCache(ObjectNode payload, ChatRequest request) {
        if (request.promptCache() == null) return;
        payload.put("prompt_cache_key", request.promptCache().key());
        if (request.promptCache().retention() != null) {
            payload.put("prompt_cache_retention", request.promptCache().retention());
        }
    }

    private void writeToolChoice(ObjectNode payload, ToolChoice choice) {
        switch (choice.mode()) {
            case AUTO -> payload.put("tool_choice", "auto");
            case NONE -> payload.put("tool_choice", "none");
            case REQUIRED -> payload.put("tool_choice", "required");
            case NAMED -> payload.putObject("tool_choice")
                    .put("type", "function")
                    .putObject("function")
                    .put("name", choice.name());
        }
    }

    private void writeResponseFormat(ObjectNode payload, ResponseFormat format) {
        ObjectNode target = payload.putObject("response_format");
        switch (format.type()) {
            case TEXT -> target.put("type", "text");
            case JSON_OBJECT -> target.put("type", "json_object");
            case JSON_SCHEMA -> {
                target.put("type", "json_schema");
                ObjectNode schema = target.putObject("json_schema");
                schema.put("name", format.name());
                if (format.description() != null) schema.put("description", format.description());
                schema.set("schema", objectMapper.valueToTree(format.schema()));
                if (format.strict() != null) schema.put("strict", format.strict());
            }
        }
    }

    private HttpRequest.Builder requestBuilder(ObjectNode payload,
                                               com.llmrix.model.router.core.routing.RoutingHints hints) {
        String body = write(payload);
        if (LOGGER.isLoggable(System.Logger.Level.DEBUG)) {
            java.util.List<String> fields = new java.util.ArrayList<>();
            payload.fieldNames().forEachRemaining(fields::add);
            LOGGER.log(System.Logger.Level.DEBUG,
                    "OpenAI request: api={0}, model={1}, stream={2}, bytes={3}, fields={4}",
                    api, modelName, payload.path("stream").asBoolean(false),
                    body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                    fields);
        }
        HttpRequest.Builder httpRequest = HttpRequest.newBuilder(completionsUri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(httpRequest::header);
        if (forwardRoutingHints) {
            String encodedHints = RoutingHintsHttpCodec.encode(hints);
            if (encodedHints != null) httpRequest.header(RoutingHintsHttpCodec.HEADER, encodedHints);
        }
        Map<String, String> authenticationHeaders = authenticator.headers();
        if (authenticationHeaders == null) {
            throw new IllegalStateException("request authenticator returned null headers");
        }
        authenticationHeaders.forEach(httpRequest::setHeader);
        return httpRequest;
    }

    private static RequestAuthenticator bearerToken(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return RequestAuthenticator.NONE;
        return () -> java.util.Collections.singletonMap("Authorization", "Bearer " + apiKey);
    }

    private ChatResponse parse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (api == Api.RESPONSES) return parseResponses(root);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new ModelUnavailableException("invalid OpenAI-compatible response: choices must be a non-empty array");
            }
            JsonNode first = choices.path(0);
            JsonNode responseMessage = first.path("message");
            String text = responseMessage.path("content").asText("");
            String finishReason = first.path("finish_reason").asText(null);
            ArrayList<ToolCallPart> toolCalls = new ArrayList<>();
            for (JsonNode call : responseMessage.path("tool_calls")) {
                if (!"function".equals(call.path("type").asText("function"))) continue;
                JsonNode function = call.path("function");
                toolCalls.add(new ToolCallPart(
                        call.path("id").asText(),
                        function.path("name").asText(),
                        function.path("arguments").asText("")));
            }
            String responseModel = root.path("model").asText(modelName);
            JsonNode usage = root.path("usage");
            Usage tokenUsage = usage.isMissingNode() ? Usage.UNKNOWN : OpenAiUsageMapper.chat(usage);
            return new ChatResponse(text, responseModel, tokenUsage,
                    Map.of("provider", "openai-compatible"), toolCalls,
                    finishReason == null ? (toolCalls.isEmpty() ? "stop" : "tool_calls") : finishReason);
        } catch (IOException e) {
            throw new ModelUnavailableException("invalid OpenAI-compatible response", e);
        }
    }

    private ChatResponse parseResponses(JsonNode root) {
        StringBuilder text = new StringBuilder();
        ArrayList<ToolCallPart> toolCalls = new ArrayList<>();
        for (JsonNode output : root.path("output")) {
            String type = output.path("type").asText();
            if ("function_call".equals(type)) {
                toolCalls.add(new ToolCallPart(output.path("call_id").asText(output.path("id").asText()),
                        output.path("name").asText(), output.path("arguments").asText("")));
            } else if ("message".equals(type)) {
                for (JsonNode content : output.path("content")) {
                    if ("output_text".equals(content.path("type").asText())) text.append(content.path("text").asText());
                }
            }
        }
        JsonNode usage = root.path("usage");
        Usage tokenUsage = usage.isMissingNode() ? Usage.UNKNOWN : OpenAiUsageMapper.responses(usage);
        String status = root.path("status").asText("completed");
        return new ChatResponse(text.toString(), root.path("model").asText(modelName), tokenUsage,
                Map.of("provider", "openai-compatible", "api", "responses"), toolCalls,
                toolCalls.isEmpty() ? ("completed".equals(status) ? "stop" : status) : "tool_calls");
    }


    private String write(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (IOException e) {
            throw new IllegalStateException("cannot serialize request", e);
        }
    }

    private static URI endpointUri(String baseUrl, Api api) {
        String normalized = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        return URI.create(normalized).resolve(api == Api.RESPONSES ? "responses" : "chat/completions");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static String truncate(String value) {
        if (value == null) return "";
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private static Map<String, String> sanitizeHeaders(Map<String, String> value, boolean forwardHints) {
        if (value == null || value.isEmpty()) return Map.of();
        if (forwardHints) return Map.copyOf(value);
        Map<String, String> filtered = new java.util.LinkedHashMap<>();
        value.forEach((key, headerValue) -> {
            if (key != null && !RoutingHintsHttpCodec.HEADER.equalsIgnoreCase(key)) {
                filtered.put(key, headerValue);
            }
        });
        return Map.copyOf(filtered);
    }

    public static final class Builder {
        private String modelName;
        private String baseUrl = "https://api.openai.com/v1";
        private String apiKey;
        private RequestAuthenticator authenticator;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration timeout = Duration.ofSeconds(60);
        private Double temperature;
        private Integer maxTokens;
        private Map<String, String> headers = Map.of();
        private HttpClient httpClient;
        private ObjectMapper objectMapper;
        private Map<String, Object> extensions = Map.of();
        private Api api = Api.CHAT_COMPLETIONS;
        private boolean forwardRoutingHints = true;

        public Builder modelName(String value) {
            modelName = value;
            return this;
        }

        public Builder baseUrl(String value) {
            baseUrl = value;
            return this;
        }

        public Builder apiKey(String value) {
            apiKey = value;
            return this;
        }

        public Builder authenticator(RequestAuthenticator value) {
            authenticator = Objects.requireNonNull(value);
            return this;
        }

        public Builder connectTimeout(Duration value) {
            connectTimeout = Objects.requireNonNull(value);
            return this;
        }

        public Builder timeout(Duration value) {
            timeout = Objects.requireNonNull(value);
            return this;
        }

        public Builder temperature(Double value) {
            temperature = value;
            return this;
        }

        public Builder maxTokens(Integer value) {
            maxTokens = value;
            return this;
        }

        public Builder headers(Map<String, String> value) {
            headers = Objects.requireNonNull(value);
            return this;
        }

        public Builder httpClient(HttpClient value) {
            httpClient = value;
            return this;
        }

        public Builder objectMapper(ObjectMapper value) {
            objectMapper = value;
            return this;
        }

        public Builder extensions(Map<String, Object> value) {
            extensions = Objects.requireNonNull(value);
            return this;
        }

        public Builder forwardRoutingHints(boolean value) {
            forwardRoutingHints = value;
            return this;
        }

        public Builder api(Api value) {
            api = Objects.requireNonNull(value, "api");
            return this;
        }

        public Builder responsesApi() {
            api = Api.RESPONSES;
            return this;
        }

        public OpenAiCompatibleChatModel build() {
            return new OpenAiCompatibleChatModel(this);
        }
    }
}
