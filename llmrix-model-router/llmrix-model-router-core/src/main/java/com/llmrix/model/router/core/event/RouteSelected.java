package com.llmrix.model.router.core.event;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@EqualsAndHashCode
@Accessors(fluent = true)
public final class RouteSelected {
    private final String requestId;
    private final String targetId;
    private final String strategy;
    private final String reason;

    public RouteSelected(String requestId, String targetId, String strategy, String reason) {
        this.requestId = requestId;
        this.targetId = targetId;
        this.strategy = strategy;
        this.reason = reason;
    }

    public RouteSelected(String requestId, String targetId, String strategy) {
        this(requestId, targetId, strategy, null);
    }

}
