package com.llmrix.model.router.server;

import java.util.Map;
import java.util.Objects;

/** Minimal identity and policy context returned by an API-key verifier. */
public record AuthenticationResult(boolean authenticated, String principal,
                                   String quotaKey, Map<String, Object> attributes) {
    public static final String REQUEST_ATTRIBUTE = AuthenticationResult.class.getName();

    public AuthenticationResult {
        if (authenticated && (principal == null || principal.isBlank())) {
            throw new IllegalArgumentException("principal is required for an authenticated result");
        }
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        quotaKey = quotaKey == null || quotaKey.isBlank() ? principal : quotaKey;
    }

    public static AuthenticationResult authenticated(String principal) {
        return authenticated(principal, principal, Map.of());
    }

    public static AuthenticationResult authenticated(String principal, String quotaKey,
                                                     Map<String, Object> attributes) {
        return new AuthenticationResult(true, Objects.requireNonNull(principal, "principal"), quotaKey, attributes);
    }

    public static AuthenticationResult rejected() {
        return new AuthenticationResult(false, null, null, Map.of());
    }
}
