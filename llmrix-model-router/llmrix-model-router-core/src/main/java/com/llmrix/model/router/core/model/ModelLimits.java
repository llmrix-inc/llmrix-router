package com.llmrix.model.router.core.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@EqualsAndHashCode
@Accessors(fluent = true)
public final class ModelLimits {
    public static final ModelLimits UNLIMITED = new ModelLimits(null, null, null);

    private final Long requestsPerMinute;
    private final Long tokensPerMinute;
    private final Integer maxConcurrency;

    public ModelLimits(Long requestsPerMinute, Long tokensPerMinute, Integer maxConcurrency) {
        if (requestsPerMinute != null && requestsPerMinute < 1) {
            throw new IllegalArgumentException("requestsPerMinute must be > 0");
        }
        if (tokensPerMinute != null && tokensPerMinute < 1) {
            throw new IllegalArgumentException("tokensPerMinute must be > 0");
        }
        if (maxConcurrency != null && maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency must be > 0");
        }
        this.requestsPerMinute = requestsPerMinute;
        this.tokensPerMinute = tokensPerMinute;
        this.maxConcurrency = maxConcurrency;
    }

}
