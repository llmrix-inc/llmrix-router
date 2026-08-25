package com.llmrix.model.router.integrations.auth;

import com.llmrix.model.router.core.spi.auth.AuthenticationContext;
import com.llmrix.model.router.core.spi.auth.ProviderAuthenticator;
import com.llmrix.model.router.core.spi.auth.RequestAuthenticator;

import java.util.Map;
import java.util.Collections;

public final class BearerTokenAuthenticator implements ProviderAuthenticator {
    public static final String ID = "bearer";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public RequestAuthenticator create(AuthenticationContext context) {
        String token = context.apiKey();
        return token == null || token.isBlank()
                ? RequestAuthenticator.NONE
                : () -> Collections.singletonMap("Authorization", "Bearer " + token);
    }
}
