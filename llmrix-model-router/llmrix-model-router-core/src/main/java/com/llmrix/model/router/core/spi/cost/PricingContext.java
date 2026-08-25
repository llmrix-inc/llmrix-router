package com.llmrix.model.router.core.spi.cost;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Getter
@Accessors(fluent = true)
public final class PricingContext {
    private final String integrationId;
    private final String providerId;
    private final String modelName;
    private final Map<String, Object> options;

    public PricingContext(String integrationId, String providerId, String modelName,
                          Map<String, Object> options) {
        this.integrationId = integrationId;
        this.providerId = providerId;
        this.modelName = modelName;
        this.options = options == null || options.isEmpty()
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(options));
    }

}
