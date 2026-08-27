package com.llmrix.model.router.integrations.provider;

import com.llmrix.model.router.core.api.ModelClient;
import com.llmrix.model.router.core.spi.auth.RequestAuthenticator;
import com.llmrix.model.router.core.spi.provider.ModelProvider;
import com.llmrix.model.router.core.spi.provider.ModelProviderRequest;
import com.llmrix.model.router.integrations.openai.OpenAiModelFactory;
import com.llmrix.model.router.integrations.openai.OpenAiModelOptions;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Collections;

/**
 * Shared OpenAI-protocol provider used by OpenAI, DeepSeek and OpenRouter.
 */
public final class OpenAiCompatibleModelProvider implements ModelProvider {
    private final String id;
    private final String defaultBaseUrl;

    public OpenAiCompatibleModelProvider(String id, String defaultBaseUrl) {
        this.id = requireText(id, "id").toLowerCase(Locale.ROOT);
        this.defaultBaseUrl = requireText(defaultBaseUrl, "defaultBaseUrl");
    }

    public static OpenAiCompatibleModelProvider openAi() {
        return new OpenAiCompatibleModelProvider("openai", "https://api.openai.com/v1");
    }

    public static OpenAiCompatibleModelProvider deepSeek() {
        return new OpenAiCompatibleModelProvider("deepseek", "https://api.deepseek.com/v1");
    }

    public static OpenAiCompatibleModelProvider openRouter() {
        return new OpenAiCompatibleModelProvider("openrouter", "https://openrouter.ai/api/v1");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String defaultBaseUrl() {
        return defaultBaseUrl;
    }

    @Override
    public ModelClient create(ModelProviderRequest request) {
        String baseUrl = hasText(request.baseUrl()) ? request.baseUrl() : defaultBaseUrl;
        Map<String, String> headers = providerHeaders(request.providerOptions());
        boolean responsesApi = "responses".equalsIgnoreCase(stringOption(request.providerOptions(), "api-mode"));
        boolean forwardRoutingHints = booleanOption(request.providerOptions(), "forward-routing-hints")
                || booleanOption(request.providerOptions(), "forwardRoutingHints");
        OpenAiModelOptions options = new OpenAiModelOptions(baseUrl, request.modelName(), null,
                request.authenticator(), headers, null, null, null, responsesApi, request.modelOptions(), forwardRoutingHints);
        ModelClient.Builder client = ModelClient.builder().chat(OpenAiModelFactory.chat(options));
        // OpenRouter and OpenAI expose the OpenAI-compatible embeddings endpoint.
        // Other compatible integrations may only implement chat.
        if ("openai".equals(id) || "openrouter".equals(id)) {
            client.embeddings(OpenAiModelFactory.embedding(options));
        }
        // Rerank follows the common Cohere/Jina-compatible POST /rerank contract.
        client.rerank(OpenAiModelFactory.rerank(options));
        if ("openai".equals(id)) {
            client.audio(OpenAiModelFactory.audio(options));
            client.images(OpenAiModelFactory.image(options));
            client.videos(OpenAiModelFactory.video(options));
        }
        return client.build();
    }

    @Override
    public void validate(ModelProviderRequest request) {
        String mode = stringOption(request.providerOptions(), "api-mode");
        if (mode != null && !"chat_completions".equalsIgnoreCase(mode) && !"responses".equalsIgnoreCase(mode)) {
            throw new IllegalArgumentException("provider option api-mode must be chat_completions or responses");
        }
    }

    private Map<String, String> providerHeaders(Map<String, Object> options) {
        if (!"openrouter".equals(id)) return Collections.emptyMap();
        Map<String, String> headers = new LinkedHashMap<>();
        putHeader(headers, "HTTP-Referer", stringOption(options, "site-url"));
        putHeader(headers, "X-Title", stringOption(options, "app-name"));
        return Collections.unmodifiableMap(headers);
    }

    private static String stringOption(Map<String, Object> options, String name) {
        Object value = options.get(name);
        return value == null ? null : Objects.toString(value);
    }

    private static boolean booleanOption(Map<String, Object> options, String name) {
        Object value = options.get(name);
        if (value instanceof Boolean bool) return bool;
        return value != null && Boolean.parseBoolean(Objects.toString(value));
    }

    private static void putHeader(Map<String, String> headers, String name, String value) {
        if (hasText(value)) headers.put(name, value);
    }

    private static String requireText(String value, String name) {
        if (!hasText(value)) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
