package com.llmrix.model.orion.client;

import com.llmrix.model.orion.model.RouterModel;
import com.llmrix.model.orion.observation.OrionModelClientListener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmrix.model.router.core.api.ChatModel;
import com.llmrix.model.router.core.api.ChatRequest;
import com.llmrix.model.router.core.api.ChatResponse;
import com.llmrix.model.router.core.api.ChatChunk;
import com.llmrix.model.router.integrations.openai.OpenAiCompatibleChatModel;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;

/** Lightweight Java client for a remote OpenAI-compatible LLM Router server. */
public final class OrionModelClient {
    private final URI baseUri;
    private final String apiKey;
    private final String defaultModel;
    private final Duration timeout;
    private final Map<String, String> headers;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OrionModelClientListener listener;
    private final Map<ModelKey, ChatModel> models = new ConcurrentHashMap<>();

    private OrionModelClient(Builder builder) {
        String baseUrl = requireText(builder.baseUrl, "baseUrl");
        this.baseUri = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        this.apiKey = builder.apiKey;
        this.defaultModel = builder.defaultModel;
        this.timeout = requirePositive(builder.timeout, "timeout");
        this.headers = validateHeaders(builder.headers);
        this.objectMapper = builder.objectMapper == null ? new ObjectMapper() : builder.objectMapper;
        this.listener = builder.listener;
        this.httpClient = builder.httpClient == null
                ? HttpClient.newBuilder().connectTimeout(requirePositive(builder.connectTimeout, "connectTimeout")).build()
                : builder.httpClient;
    }

    public static Builder builder() {
        return new Builder();
    }

    public ChatModel chatModel(String model) {
        return model(model, false);
    }

    public ChatModel chatModel(String model, OrionModelRequestOptions options) {
        return model(model, false, options);
    }

    public ChatModel responsesModel(String model) {
        return model(model, true);
    }

    public ChatModel responsesModel(String model, OrionModelRequestOptions options) {
        return model(model, true, options);
    }

    public ChatModel defaultChatModel() {
        return chatModel(requireText(defaultModel, "defaultModel"));
    }

    public ChatResponse chat(ChatRequest request) {
        return defaultChatModel().chat(request);
    }

    public ChatResponse chat(ChatRequest request, OrionModelRequestOptions options) {
        return chatModel(requireText(defaultModel, "defaultModel"), options).chat(request);
    }

    public CompletionStage<ChatResponse> chatAsync(ChatRequest request) {
        return defaultChatModel().chatAsync(request);
    }

    public CompletionStage<ChatResponse> chatAsync(ChatRequest request, OrionModelRequestOptions options) {
        return chatModel(requireText(defaultModel, "defaultModel"), options).chatAsync(request);
    }

    public Flow.Publisher<ChatChunk> stream(ChatRequest request) {
        return defaultChatModel().stream(request);
    }

    public Flow.Publisher<ChatChunk> stream(ChatRequest request, OrionModelRequestOptions options) {
        return chatModel(requireText(defaultModel, "defaultModel"), options).stream(request);
    }

    public List<RouterModel> models() {
        return models(OrionModelRequestOptions.DEFAULT);
    }

    public List<RouterModel> models(OrionModelRequestOptions options) {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve("models"))
                .timeout(timeout).GET().header("Accept", "application/json");
        authorize(request);
        mergedHeaders(options).forEach(request::header);
        try {
            HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new OrionModelClientException(errorMessage(response.body()), response.statusCode());
            }
            JsonNode data = objectMapper.readTree(response.body()).path("data");
            if (!data.isArray()) throw new OrionModelClientException("invalid models response: data must be an array", response.statusCode());
            List<RouterModel> result = new ArrayList<>();
            for (JsonNode item : data) {
                String id = item.path("id").asText(null);
                if (id != null && !id.isBlank()) {
                    result.add(new RouterModel(id, item.path("owned_by").asText(null)));
                }
            }
            return List.copyOf(result);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new OrionModelClientException("models request was interrupted", error);
        } catch (IOException error) {
            throw new OrionModelClientException("models request failed", error);
        }
    }

    private ChatModel model(String model, boolean responsesApi) {
        String modelName = requireText(model, "model");
        return models.computeIfAbsent(new ModelKey(modelName, responsesApi), key -> createModel(key, headers));
    }

    private ChatModel model(String model, boolean responsesApi, OrionModelRequestOptions options) {
        Objects.requireNonNull(options, "options");
        if (options.headers().isEmpty()) return model(model, responsesApi);
        return createModel(new ModelKey(requireText(model, "model"), responsesApi), mergedHeaders(options));
    }

    private Map<String, String> mergedHeaders(OrionModelRequestOptions options) {
        Objects.requireNonNull(options, "options");
        Map<String, String> merged = new java.util.LinkedHashMap<>(headers);
        merged.putAll(validateHeaders(options.headers()));
        return Map.copyOf(merged);
    }

    private ChatModel createModel(ModelKey key, Map<String, String> requestHeaders) {
            OpenAiCompatibleChatModel.Builder builder = OpenAiCompatibleChatModel.builder()
                    .baseUrl(baseUri.toString())
                    .apiKey(apiKey)
                    .modelName(key.model())
                    .timeout(timeout)
                    .headers(requestHeaders)
                    .httpClient(httpClient)
                    .objectMapper(objectMapper);
            if (key.responsesApi()) builder.responsesApi();
            ChatModel model = builder.build();
            if (listener == OrionModelClientListener.NOOP) return model;
            return new ObservingChatModel(model, listener, requestId(requestHeaders),
                    key.responsesApi() ? "responses" : "chat.completions", key.model());
    }

    private static String requestId(Map<String, String> headers) {
        return headers.entrySet().stream()
                .filter(entry -> "x-request-id".equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private void authorize(HttpRequest.Builder request) {
        if (apiKey != null && !apiKey.isBlank()) request.header("Authorization", "Bearer " + apiKey);
    }

    private String errorMessage(String body) {
        try {
            String message = objectMapper.readTree(body).path("error").path("message").asText(null);
            return message == null || message.isBlank() ? "LLM Router request failed" : message;
        } catch (Exception ignored) {
            return "LLM Router request failed";
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static Map<String, String> validateHeaders(Map<String, String> values) {
        Objects.requireNonNull(values, "headers");
        Map<String, String> validated = new java.util.LinkedHashMap<>();
        values.forEach((name, value) -> {
            String safeName = requireText(name, "header name");
            String safeValue = requireText(value, "header value");
            if (safeName.indexOf('\r') >= 0 || safeName.indexOf('\n') >= 0
                    || safeValue.indexOf('\r') >= 0 || safeValue.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("headers must not contain line breaks");
            }
            if ("authorization".equalsIgnoreCase(safeName)) {
                throw new IllegalArgumentException("Authorization is managed by the client API key");
            }
            validated.put(safeName, safeValue);
        });
        return Map.copyOf(validated);
    }

    private record ModelKey(String model, boolean responsesApi) { }

    public static final class Builder {
        private String baseUrl = "http://localhost:8080/v1";
        private String apiKey;
        private String defaultModel;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration timeout = Duration.ofSeconds(60);
        private Map<String, String> headers = Map.of();
        private HttpClient httpClient;
        private ObjectMapper objectMapper;
        private OrionModelClientListener listener = OrionModelClientListener.NOOP;

        public Builder baseUrl(String value) { baseUrl = value; return this; }
        public Builder apiKey(String value) { apiKey = value; return this; }
        public Builder defaultModel(String value) { defaultModel = value; return this; }
        public Builder connectTimeout(Duration value) { connectTimeout = value; return this; }
        public Builder timeout(Duration value) { timeout = value; return this; }
        public Builder headers(Map<String, String> value) { headers = Objects.requireNonNull(value); return this; }
        public Builder httpClient(HttpClient value) { httpClient = value; return this; }
        public Builder objectMapper(ObjectMapper value) { objectMapper = value; return this; }
        public Builder listener(OrionModelClientListener value) { listener = Objects.requireNonNull(value); return this; }
        public OrionModelClient build() { return new OrionModelClient(this); }
    }
}
