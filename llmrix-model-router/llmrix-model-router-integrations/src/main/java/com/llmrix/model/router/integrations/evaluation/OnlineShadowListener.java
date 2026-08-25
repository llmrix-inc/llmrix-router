package com.llmrix.model.router.integrations.evaluation;

public interface OnlineShadowListener {
    OnlineShadowListener NOOP = (model, durationNanos, success, errorType) -> {
    };

    void completed(String model, long durationNanos, boolean success, String errorType);
}
