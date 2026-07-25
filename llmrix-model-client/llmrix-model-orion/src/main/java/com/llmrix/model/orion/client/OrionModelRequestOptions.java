package com.llmrix.model.orion.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Per-request transport options that do not change model semantics. */
public final class OrionModelRequestOptions {
    public static final OrionModelRequestOptions DEFAULT = new OrionModelRequestOptions(Map.of());
    private final Map<String, String> headers;

    private OrionModelRequestOptions(Map<String, String> headers) {
        this.headers = Map.copyOf(headers);
    }

    public static Builder builder() { return new Builder(); }
    public Map<String, String> headers() { return headers; }

    public static final class Builder {
        private final Map<String, String> headers = new LinkedHashMap<>();

        public Builder requestId(String requestId) {
            return header("X-Request-Id", requireText(requestId, "requestId"));
        }

        public Builder header(String name, String value) {
            String normalized = requireText(name, "header name");
            if ("authorization".equalsIgnoreCase(normalized)) {
                throw new IllegalArgumentException("Authorization is managed by the client API key");
            }
            headers.put(normalized, requireText(value, "header value"));
            return this;
        }

        public Builder headers(Map<String, String> values) {
            Objects.requireNonNull(values, "headers").forEach(this::header);
            return this;
        }

        public OrionModelRequestOptions build() {
            return headers.isEmpty() ? DEFAULT : new OrionModelRequestOptions(headers);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank() || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(name + " must be safe non-blank text");
        }
        return value;
    }
}
