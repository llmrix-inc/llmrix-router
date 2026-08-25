package com.llmrix.model.router.core.spi.provider;

import com.llmrix.model.router.core.spi.auth.RequestAuthenticator;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Getter
@Accessors(fluent = true)
public final class ModelProviderRequest {
    private final String integrationId;
    private final String modelName;
    private final String baseUrl;
    private final RequestAuthenticator authenticator;
    private final Map<String, Object> providerOptions;
    private final Map<String, Object> modelOptions;

    public ModelProviderRequest(String integrationId, String modelName, String baseUrl,
                                RequestAuthenticator authenticator,
                                Map<String, Object> providerOptions,
                                Map<String, Object> modelOptions) {
        this.integrationId = integrationId;
        this.modelName = modelName;
        this.baseUrl = baseUrl;
        this.authenticator = authenticator == null ? RequestAuthenticator.NONE : authenticator;
        this.providerOptions = immutableMap(providerOptions);
        this.modelOptions = immutableMap(modelOptions);
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> value) {
        if (value == null || value.isEmpty()) return Collections.emptyMap();
        return Collections.unmodifiableMap(new LinkedHashMap<K, V>(value));
    }
}
