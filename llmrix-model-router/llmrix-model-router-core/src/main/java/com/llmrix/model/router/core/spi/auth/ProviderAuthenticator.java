package com.llmrix.model.router.core.spi.auth;

/**
 * Creates request authenticators for a configured authentication scheme.
 */
public interface ProviderAuthenticator {
    String id();

    RequestAuthenticator create(AuthenticationContext context);
}
