package com.llmrix.model.router.integrations.fugu;

public record FuguRetry(String requestId, String candidateId, int nextAttempt, String errorType) { }
