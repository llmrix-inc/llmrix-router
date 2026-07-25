package com.llmrix.model.router.core.spi.event;

import com.llmrix.model.router.core.api.ChatRequest;

public record RequestStarted(String requestId, long startedNanos, ChatRequest request, String route) {
    public RequestStarted(String requestId, long startedNanos) {
        this(requestId, startedNanos, null, null);
    }
}
