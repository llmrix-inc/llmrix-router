package com.llmrix.model.router.core.spi.event;

public record FirstTokenReceived(String requestId, String candidateId, long durationNanos) { }
