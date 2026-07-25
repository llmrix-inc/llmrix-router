package com.llmrix.model.router.core.spi.event;
public record FallbackStarted(String requestId, String fromCandidate, String reason) {}
