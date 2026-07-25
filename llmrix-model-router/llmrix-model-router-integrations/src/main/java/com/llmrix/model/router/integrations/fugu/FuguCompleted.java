package com.llmrix.model.router.integrations.fugu;

public record FuguCompleted(String requestId, int turns, long durationNanos,
                            boolean success, String termination, String errorType) { }
