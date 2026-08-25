package com.llmrix.model.router.core.spi;

import com.llmrix.model.router.core.runtime.LlmRouterBuilder;

/** Service-provider hook for optional integrations to register concrete defaults. */
public interface RouterIntegrationDefaults {
    void configure(LlmRouterBuilder builder);
}
