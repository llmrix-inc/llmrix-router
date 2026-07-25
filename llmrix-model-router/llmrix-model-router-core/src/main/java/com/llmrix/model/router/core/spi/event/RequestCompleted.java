package com.llmrix.model.router.core.spi.event;
public record RequestCompleted(String requestId, String candidateId, long durationNanos, boolean success, int attempts) {}
