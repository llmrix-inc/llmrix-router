package com.llmrix.model.router.spring.boot.http.security;

@FunctionalInterface
public interface ApiKeyVerifier {
    boolean verify(String apiKey);

    /**
     * Resolves the authenticated caller while preserving the original boolean
     * verifier contract for existing integrations.
     */
    default AuthenticationResult authenticate(String apiKey) {
        return verify(apiKey) ? AuthenticationResult.authenticated("api-key") : AuthenticationResult.rejected();
    }
}
