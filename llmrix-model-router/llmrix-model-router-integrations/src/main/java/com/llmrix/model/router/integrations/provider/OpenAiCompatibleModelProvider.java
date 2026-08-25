package com.llmrix.model.router.integrations.provider;

import com.llmrix.model.router.core.api.ModelClient;
import com.llmrix.model.router.core.spi.auth.RequestAuthenticator;
import com.llmrix.model.router.core.spi.provider.ModelProvider;
import com.llmrix.model.router.core.spi.provider.ModelProviderRequest;
import com.llmrix.model.router.integrations.openai.OpenAiCompatibleAudioModel;
import com.llmrix.model.router.integrations.openai.OpenAiCompatibleChatModel;
import com.llmrix.model.router.integrations.openai.OpenAiCompatibleEmbeddingModel;
import com.llmrix.model.router.integrations.openai.OpenAiCompatibleImageModel;
import com.llmrix.model.router.integrations.openai.OpenAiCompatibleVideoModel;
import com.llmrix.model.router.integrations.openai.OpenAiTransport;

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
        OpenAiCompatibleChatModel.Builder builder = OpenAiCompatibleChatModel.builder()
                .modelName(request.modelName())
                .baseUrl(baseUrl)
                .authenticator(request.authenticator())
                .headers(headers)
                .extensions(request.modelOptions());
        if ("responses".equalsIgnoreCase(stringOption(request.providerOptions(), "api-mode"))) {
            builder.responsesApi();
        }
        ModelClient.Builder client = ModelClient.builder().chat(builder.build());
        OpenAiTransport transport = new OpenAiTransport(baseUrl, request.authenticator(), headers);
        // OpenRouter exposes chat completions, but does not publish embedding
        // models through its current /v1/models catalog or embeddings endpoint.
        if ("openai".equals(id)) {
            client.embeddings(new OpenAiCompatibleEmbeddingModel(request.modelName(), transport));
        }
        if ("openai".equals(id)) {
            client.audio(new OpenAiCompatibleAudioModel(request.modelName(), transport));
            client.images(new OpenAiCompatibleImageModel(request.modelName(), transport));
            client.videos(new OpenAiCompatibleVideoModel(request.modelName(), transport));
        }
        return client.build();
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
