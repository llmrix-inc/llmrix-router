package com.llmrix.model.router.core.spi.auth;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Getter
@Accessors(fluent = true)
public final class AuthenticationContext {

    private final String integrationId;
    private final String providerId;
    private final String apiKey;
    private final Map<String, Object> options;

    public AuthenticationContext(String integrationId, String providerId, String apiKey,
                                 Map<String, Object> options) {
        this.integrationId = integrationId;
        this.providerId = providerId;
        this.apiKey = apiKey;
        this.options = immutableMap(options);
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> value) {
        if (value == null || value.isEmpty()) return Collections.emptyMap();
        return Collections.unmodifiableMap(new LinkedHashMap<K, V>(value));
    }
}
