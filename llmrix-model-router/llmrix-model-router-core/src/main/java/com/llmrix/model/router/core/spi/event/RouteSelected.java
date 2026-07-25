package com.llmrix.model.router.core.spi.event;
public record RouteSelected(String requestId, String candidateId, String strategy, String reason) {
    public RouteSelected(String requestId, String candidateId, String strategy) {
        this(requestId, candidateId, strategy, null);
    }
}
