package com.llmrix.model.router.core.spi.provider;

import com.llmrix.model.router.core.api.ModelClient;

/**
 * Creates model clients for one provider type. Implementations are registry components.
 */
public interface ModelProvider {
    String id();

    String defaultBaseUrl();

    ModelClient create(ModelProviderRequest request);

    /** Validates provider and model options before creating network adapters. */
    default void validate(ModelProviderRequest request) {
    }
}
