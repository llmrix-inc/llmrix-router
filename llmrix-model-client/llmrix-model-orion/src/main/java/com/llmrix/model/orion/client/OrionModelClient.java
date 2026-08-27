package com.llmrix.model.orion.client;

import com.llmrix.model.orion.model.RouterModel;
import com.llmrix.model.orion.observation.OrionModelClientListener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmrix.model.router.core.api.audio.AudioModel;
import com.llmrix.model.router.core.api.audio.AudioResponse;
import com.llmrix.model.router.core.api.audio.AudioTextRequest;
import com.llmrix.model.router.core.api.audio.SpeechRequest;
import com.llmrix.model.router.core.api.chat.ChatModel;
import com.llmrix.model.router.core.api.chat.ChatRequest;
import com.llmrix.model.router.core.api.chat.ChatResponse;
import com.llmrix.model.router.core.api.chat.ChatChunk;
import com.llmrix.model.router.core.api.embedding.EmbeddingModel;
import com.llmrix.model.router.core.api.embedding.EmbeddingRequest;
import com.llmrix.model.router.core.api.embedding.EmbeddingResponse;
import com.llmrix.model.router.core.api.rerank.RerankModel;
import com.llmrix.model.router.core.api.rerank.RerankRequest;
import com.llmrix.model.router.core.api.rerank.RerankResponse;
import com.llmrix.model.router.core.api.image.ImageEditRequest;
import com.llmrix.model.router.core.api.image.ImageModel;
import com.llmrix.model.router.core.api.image.ImageRequest;
import com.llmrix.model.router.core.api.image.ImageResponse;
import com.llmrix.model.router.core.api.video.VideoContent;
import com.llmrix.model.router.core.api.video.VideoLookupRequest;
import com.llmrix.model.router.core.api.video.VideoModel;
import com.llmrix.model.router.core.api.video.VideoRemixRequest;
import com.llmrix.model.router.core.api.video.VideoRequest;
import com.llmrix.model.router.core.api.video.VideoResponse;
import com.llmrix.model.router.core.spi.auth.RequestAuthenticator;
import com.llmrix.model.router.integrations.openai.OpenAiModelFactory;
import com.llmrix.model.router.integrations.openai.OpenAiModelOptions;

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
    private final String defaultEmbeddingModel;
    private final String defaultRerankModel;
    private final String defaultAudioModel;
    private final String defaultImageModel;
    private final String defaultVideoModel;
    private final Duration timeout;
    private final Map<String, String> headers;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OrionModelClientListener listener;
    private final Map<ModelKey, ChatModel> chatModels = new ConcurrentHashMap<>();
    private final Map<String, EmbeddingModel> embeddingModels = new ConcurrentHashMap<>();
    private final Map<String, RerankModel> rerankModels = new ConcurrentHashMap<>();
    private final Map<String, AudioModel> audioModels = new ConcurrentHashMap<>();
    private final Map<String, ImageModel> imageModels = new ConcurrentHashMap<>();
    private final Map<String, VideoModel> videoModels = new ConcurrentHashMap<>();

    private OrionModelClient(Builder builder) {
        String baseUrl = requireText(builder.baseUrl, "baseUrl");
        this.baseUri = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        this.apiKey = builder.apiKey;
        this.defaultModel = builder.defaultModel;
        this.defaultEmbeddingModel = builder.defaultEmbeddingModel;
        this.defaultRerankModel = builder.defaultRerankModel;
        this.defaultAudioModel = builder.defaultAudioModel;
        this.defaultImageModel = builder.defaultImageModel;
        this.defaultVideoModel = builder.defaultVideoModel;
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

    public EmbeddingModel embeddingModel(String model) {
        String modelName = requireText(model, "model");
        return embeddingModels.computeIfAbsent(modelName, this::createEmbeddingModel);
    }

    public EmbeddingModel embeddingModel(String model, OrionModelRequestOptions options) {
        Objects.requireNonNull(options, "options");
        if (options.headers().isEmpty()) return embeddingModel(model);
        return createEmbeddingModel(requireText(model, "model"), mergedHeaders(options));
    }

    public EmbeddingModel defaultEmbeddingModel() {
        return embeddingModel(defaultRoute(defaultEmbeddingModel));
    }

    public EmbeddingResponse embed(EmbeddingRequest request) {
        return defaultEmbeddingModel().embed(request);
    }

    public EmbeddingResponse embed(EmbeddingRequest request, OrionModelRequestOptions options) {
        return embeddingModel(defaultRoute(defaultEmbeddingModel), options).embed(request);
    }

    public RerankModel rerankModel(String model) {
        String modelName = requireText(model, "model");
        return rerankModels.computeIfAbsent(modelName, this::createRerankModel);
    }

    public RerankModel rerankModel(String model, OrionModelRequestOptions options) {
        Objects.requireNonNull(options, "options");
        if (options.headers().isEmpty()) return rerankModel(model);
        return createRerankModel(requireText(model, "model"), mergedHeaders(options));
    }

    public RerankModel defaultRerankModel() {
        return rerankModel(defaultRoute(defaultRerankModel));
    }

    public RerankResponse rerank(RerankRequest request) {
        return defaultRerankModel().rerank(request);
    }

    public RerankResponse rerank(RerankRequest request, OrionModelRequestOptions options) {
        return rerankModel(defaultRoute(defaultRerankModel), options).rerank(request);
    }

    public AudioModel audioModel(String model) {
        String modelName = requireText(model, "model");
        return audioModels.computeIfAbsent(modelName, this::createAudioModel);
    }

    public AudioModel audioModel(String model, OrionModelRequestOptions options) {
        Objects.requireNonNull(options, "options");
        if (options.headers().isEmpty()) return audioModel(model);
        return createAudioModel(requireText(model, "model"), mergedHeaders(options));
    }

    public AudioModel defaultAudioModel() {
        return audioModel(defaultRoute(defaultAudioModel));
    }

    public AudioResponse transcribe(AudioTextRequest request) {
        return defaultAudioModel().transcribe(request);
    }

    public AudioResponse transcribe(AudioTextRequest request, OrionModelRequestOptions options) {
        return audioModel(defaultRoute(defaultAudioModel), options).transcribe(request);
    }

    public AudioResponse translate(AudioTextRequest request) {
        return defaultAudioModel().translate(request);
    }

    public AudioResponse translate(AudioTextRequest request, OrionModelRequestOptions options) {
        return audioModel(defaultRoute(defaultAudioModel), options).translate(request);
    }

    public AudioResponse speech(SpeechRequest request) {
        return defaultAudioModel().speech(request);
    }

    public AudioResponse speech(SpeechRequest request, OrionModelRequestOptions options) {
        return audioModel(defaultRoute(defaultAudioModel), options).speech(request);
    }

    public ImageModel imageModel(String model) {
        String modelName = requireText(model, "model");
        return imageModels.computeIfAbsent(modelName, this::createImageModel);
    }

    public ImageModel imageModel(String model, OrionModelRequestOptions options) {
        Objects.requireNonNull(options, "options");
        if (options.headers().isEmpty()) return imageModel(model);
        return createImageModel(requireText(model, "model"), mergedHeaders(options));
    }

    public ImageModel defaultImageModel() {
        return imageModel(defaultRoute(defaultImageModel));
    }

    public ImageResponse generate(ImageRequest request) {
        return defaultImageModel().generate(request);
    }

    public ImageResponse generate(ImageRequest request, OrionModelRequestOptions options) {
        return imageModel(defaultRoute(defaultImageModel), options).generate(request);
    }

    public ImageResponse edit(ImageEditRequest request) {
        return defaultImageModel().edit(request);
    }

    public ImageResponse edit(ImageEditRequest request, OrionModelRequestOptions options) {
        return imageModel(defaultRoute(defaultImageModel), options).edit(request);
    }

    public VideoModel videoModel(String model) {
        String modelName = requireText(model, "model");
        return videoModels.computeIfAbsent(modelName, this::createVideoModel);
    }

    public VideoModel videoModel(String model, OrionModelRequestOptions options) {
        Objects.requireNonNull(options, "options");
        if (options.headers().isEmpty()) return videoModel(model);
        return createVideoModel(requireText(model, "model"), mergedHeaders(options));
    }

    public VideoModel defaultVideoModel() {
        return videoModel(defaultRoute(defaultVideoModel));
    }

    public VideoResponse create(VideoRequest request) {
        return defaultVideoModel().create(request);
    }

    public VideoResponse create(VideoRequest request, OrionModelRequestOptions options) {
        return videoModel(defaultRoute(defaultVideoModel), options).create(request);
    }

    public VideoResponse retrieve(VideoLookupRequest request) {
        return defaultVideoModel().retrieve(request);
    }

    public VideoResponse retrieve(VideoLookupRequest request, OrionModelRequestOptions options) {
        return videoModel(defaultRoute(defaultVideoModel), options).retrieve(request);
    }

    public VideoContent content(VideoLookupRequest request) {
        return defaultVideoModel().content(request);
    }

    public VideoContent content(VideoLookupRequest request, OrionModelRequestOptions options) {
        return videoModel(defaultRoute(defaultVideoModel), options).content(request);
    }

    public VideoResponse delete(VideoLookupRequest request) {
        return defaultVideoModel().delete(request);
    }

    public VideoResponse delete(VideoLookupRequest request, OrionModelRequestOptions options) {
        return videoModel(defaultRoute(defaultVideoModel), options).delete(request);
    }

    public VideoResponse remix(VideoRemixRequest request) {
        return defaultVideoModel().remix(request);
    }

    public VideoResponse remix(VideoRemixRequest request, OrionModelRequestOptions options) {
        return videoModel(defaultRoute(defaultVideoModel), options).remix(request);
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
        return chatModels.computeIfAbsent(new ModelKey(modelName, responsesApi), key -> createModel(key, headers));
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
            ChatModel model = OpenAiModelFactory.chat(options(key.model(), key.responsesApi(), requestHeaders));
            if (listener == OrionModelClientListener.NOOP) return model;
        return new ObservingChatModel(model, listener, requestId(requestHeaders),
                    key.responsesApi() ? "responses" : "chat.completions", key.model());
    }

    private EmbeddingModel createEmbeddingModel(String model) {
        return createEmbeddingModel(model, headers);
    }

    private EmbeddingModel createEmbeddingModel(String model, Map<String, String> requestHeaders) {
        EmbeddingModel delegate = OpenAiModelFactory.embedding(options(model, false, requestHeaders));
        return ObservingModelOperations.embedding(delegate, listener, requestId(requestHeaders), model);
    }

    private RerankModel createRerankModel(String model) {
        return createRerankModel(model, headers);
    }

    private RerankModel createRerankModel(String model, Map<String, String> requestHeaders) {
        RerankModel delegate = OpenAiModelFactory.rerank(options(model, false, requestHeaders));
        return ObservingModelOperations.rerank(delegate, listener, requestId(requestHeaders), model);
    }

    private AudioModel createAudioModel(String model) {
        return createAudioModel(model, headers);
    }

    private AudioModel createAudioModel(String model, Map<String, String> requestHeaders) {
        AudioModel delegate = OpenAiModelFactory.audio(options(model, false, requestHeaders));
        return ObservingModelOperations.audio(delegate, listener, requestId(requestHeaders), model);
    }

    private ImageModel createImageModel(String model) {
        return createImageModel(model, headers);
    }

    private ImageModel createImageModel(String model, Map<String, String> requestHeaders) {
        ImageModel delegate = OpenAiModelFactory.image(options(model, false, requestHeaders));
        return ObservingModelOperations.image(delegate, listener, requestId(requestHeaders), model);
    }

    private VideoModel createVideoModel(String model) {
        return createVideoModel(model, headers);
    }

    private VideoModel createVideoModel(String model, Map<String, String> requestHeaders) {
        VideoModel delegate = OpenAiModelFactory.video(options(model, false, requestHeaders));
        return ObservingModelOperations.video(delegate, listener, requestId(requestHeaders), model);
    }

    private OpenAiModelOptions options(String model, boolean responsesApi, Map<String, String> requestHeaders) {
        RequestAuthenticator authenticator = apiKey == null || apiKey.isBlank()
                ? RequestAuthenticator.NONE
                : () -> Map.of("Authorization", "Bearer " + apiKey);
        return new OpenAiModelOptions(baseUri.toString(), model, model, authenticator, requestHeaders,
                httpClient, objectMapper, timeout, responsesApi, Map.of(), true);
    }

    private String defaultRoute(String configured) {
        return requireText(configured, "default model");
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
        private String defaultEmbeddingModel;
        private String defaultRerankModel;
        private String defaultAudioModel;
        private String defaultImageModel;
        private String defaultVideoModel;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration timeout = Duration.ofSeconds(60);
        private Map<String, String> headers = Map.of();
        private HttpClient httpClient;
        private ObjectMapper objectMapper;
        private OrionModelClientListener listener = OrionModelClientListener.NOOP;

        public Builder baseUrl(String value) { baseUrl = value; return this; }
        public Builder apiKey(String value) { apiKey = value; return this; }
        public Builder defaultModel(String value) { defaultModel = value; return this; }
        public Builder defaultEmbeddingModel(String value) { defaultEmbeddingModel = value; return this; }
        public Builder defaultRerankModel(String value) { defaultRerankModel = value; return this; }
        public Builder defaultAudioModel(String value) { defaultAudioModel = value; return this; }
        public Builder defaultImageModel(String value) { defaultImageModel = value; return this; }
        public Builder defaultVideoModel(String value) { defaultVideoModel = value; return this; }
        public Builder connectTimeout(Duration value) { connectTimeout = value; return this; }
        public Builder timeout(Duration value) { timeout = value; return this; }
        public Builder headers(Map<String, String> value) { headers = Objects.requireNonNull(value); return this; }
        public Builder httpClient(HttpClient value) { httpClient = value; return this; }
        public Builder objectMapper(ObjectMapper value) { objectMapper = value; return this; }
        public Builder listener(OrionModelClientListener value) { listener = Objects.requireNonNull(value); return this; }
        public OrionModelClient build() { return new OrionModelClient(this); }
    }
}
