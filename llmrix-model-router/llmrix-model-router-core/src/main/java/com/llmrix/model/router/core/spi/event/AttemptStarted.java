package com.llmrix.model.router.core.spi.event;

public record AttemptStarted(String requestId, String candidateId, int attempt) { }
