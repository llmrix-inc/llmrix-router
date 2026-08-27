package com.llmrix.model.router.integrations.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmrix.model.router.core.spi.auth.RequestAuthenticator;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/** Immutable construction options shared by OpenAI-compatible model adapters. */
public record OpenAiModelOptions(
        String baseUrl,
        String modelName,
        String routeModel,
        RequestAuthenticator authenticator,
        Map<String, String> headers,
        HttpClient httpClient,
        ObjectMapper objectMapper,
        Duration timeout,
        boolean responsesApi,
        Map<String, Object> extensions,
        boolean forwardRoutingHints) {

    /**
     * Backwards-compatible constructor. Retains the historical forwarding behavior.
     */
    public OpenAiModelOptions(String baseUrl, String modelName, String routeModel,
                              RequestAuthenticator authenticator, Map<String, String> headers,
                              HttpClient httpClient, ObjectMapper objectMapper, Duration timeout,
                              boolean responsesApi, Map<String, Object> extensions) {
        this(baseUrl, modelName, routeModel, authenticator, headers, httpClient, objectMapper,
                timeout, responsesApi, extensions, true);
    }

    public OpenAiModelOptions {
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("baseUrl must not be blank");
        if (modelName == null || modelName.isBlank()) throw new IllegalArgumentException("modelName must not be blank");
        authenticator = authenticator == null ? RequestAuthenticator.NONE : authenticator;
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
        if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }
}
