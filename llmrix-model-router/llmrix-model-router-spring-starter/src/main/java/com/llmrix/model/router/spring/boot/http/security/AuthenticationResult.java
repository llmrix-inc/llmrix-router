package com.llmrix.model.router.spring.boot.http.security;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.Map;
import java.util.Objects;

/**
 * Minimal identity and policy context returned by an API-key verifier.
 */
@Getter
@EqualsAndHashCode
@Accessors(fluent = true)
public final class AuthenticationResult {
    public static final String REQUEST_ATTRIBUTE = AuthenticationResult.class.getName();

    private final boolean authenticated;
    private final String principal;
    private final String quotaKey;
    private final Map<String, Object> attributes;

    public AuthenticationResult(boolean authenticated, String principal,
                                String quotaKey, Map<String, Object> attributes) {
        if (authenticated && (principal == null || principal.isBlank())) {
            throw new IllegalArgumentException("principal is required for an authenticated result");
        }
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        quotaKey = quotaKey == null || quotaKey.isBlank() ? principal : quotaKey;
        this.authenticated = authenticated;
        this.principal = principal;
        this.quotaKey = quotaKey;
        this.attributes = attributes;
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
