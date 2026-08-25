package com.llmrix.model.router.spring.boot.http.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class BootstrapApiKeyVerifier implements ApiKeyVerifier {
    private final byte[] expected;

    public BootstrapApiKeyVerifier(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("llmrix.model.router.http.auth.bootstrap-key is required when auth is enabled");
        }
        this.expected = apiKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean verify(String apiKey) {
        byte[] actual = apiKey == null ? new byte[0] : apiKey.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }
}
