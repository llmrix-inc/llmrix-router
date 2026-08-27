package com.llmrix.model.router.integrations;

import com.llmrix.model.router.core.runtime.LlmRouterBuilder;
import com.llmrix.model.router.core.spi.RouterIntegrationDefaults;
import com.llmrix.model.router.integrations.auth.BearerTokenAuthenticator;
import com.llmrix.model.router.integrations.provider.OpenAiCompatibleModelProvider;
import com.llmrix.model.router.integrations.provider.OllamaModelProvider;

/** Registers the integrations shipped with the standard integrations artifact. */
public final class DefaultRouterIntegrations implements RouterIntegrationDefaults {
    @Override
    public void configure(LlmRouterBuilder builder) {
        builder.defaultAuthenticator(BearerTokenAuthenticator.ID)
                .provider(OpenAiCompatibleModelProvider.openAi())
                .provider(OpenAiCompatibleModelProvider.deepSeek())
                .provider(OpenAiCompatibleModelProvider.openRouter())
                .provider(new OllamaModelProvider())
                .authenticator(new BearerTokenAuthenticator());
    }
}
