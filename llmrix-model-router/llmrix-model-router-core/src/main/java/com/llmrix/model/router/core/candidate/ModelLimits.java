package com.llmrix.model.router.core.candidate;

public record ModelLimits(Long requestsPerMinute, Long tokensPerMinute, Integer maxConcurrency) {
    public static final ModelLimits UNLIMITED = new ModelLimits(null, null, null);

    public ModelLimits {
        if (requestsPerMinute != null && requestsPerMinute < 1) {
            throw new IllegalArgumentException("requestsPerMinute must be > 0");
        }
        if (tokensPerMinute != null && tokensPerMinute < 1) {
            throw new IllegalArgumentException("tokensPerMinute must be > 0");
        }
        if (maxConcurrency != null && maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency must be > 0");
        }
    }
}
