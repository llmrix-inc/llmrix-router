package com.llmrix.model.router.core.spi.event;
public record AttemptCompleted(String requestId, String candidateId, int attempt, long durationNanos, boolean success, String errorType) {}
