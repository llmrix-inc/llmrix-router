package com.llmrix.model.router.integrations.provider;

import com.llmrix.model.router.core.api.ModelClient;
import com.llmrix.model.router.core.spi.auth.RequestAuthenticator;
import com.llmrix.model.router.core.spi.provider.ModelProvider;
import com.llmrix.model.router.core.spi.provider.ModelProviderRequest;
import com.llmrix.model.router.integrations.ollama.OllamaChatModel;
import com.llmrix.model.router.integrations.ollama.OllamaEmbeddingModel;
import com.llmrix.model.router.integrations.ollama.OllamaTransport;

/** Native Ollama provider (local or remote Ollama server). */
public final class OllamaModelProvider implements ModelProvider {
    private static final String DEFAULT_BASE_URL = "http://localhost:11434";

    @Override public String id() { return "ollama"; }
    @Override public String defaultBaseUrl() { return DEFAULT_BASE_URL; }

    @Override public void validate(ModelProviderRequest request) {
        Object keepAlive = request.providerOptions().get("keep_alive");
        if (keepAlive != null && !(keepAlive instanceof String) && !(keepAlive instanceof Number)
                && !(keepAlive instanceof Boolean)) {
            throw new IllegalArgumentException("ollama keep_alive must be a string, number, or boolean");
        }
    }

    @Override public ModelClient create(ModelProviderRequest request) {
        String baseUrl = request.baseUrl() == null || request.baseUrl().isBlank()
                ? DEFAULT_BASE_URL : request.baseUrl();
        RequestAuthenticator auth = request.authenticator() == null ? RequestAuthenticator.NONE : request.authenticator();
        java.util.Map<String, Object> options = new java.util.LinkedHashMap<>(request.providerOptions());
        options.putAll(request.modelOptions());
        OllamaChatModel chat = new OllamaChatModel(request.modelName(), baseUrl, auth, options);
        OllamaTransport transport = new OllamaTransport(baseUrl, auth);
        return ModelClient.builder().chat(chat).embeddings(new OllamaEmbeddingModel(request.modelName(), transport)).build();
    }
}
