package com.llmrix.model.router.core.event;

import com.llmrix.model.router.core.api.ModelRequest;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@EqualsAndHashCode
@Accessors(fluent = true)
public final class RequestStarted {
    private final String requestId;
    private final long startedNanos;
    private final ModelRequest request;
    private final String route;

    public RequestStarted(String requestId, long startedNanos, ModelRequest request, String route) {
        this.requestId = requestId;
        this.startedNanos = startedNanos;
        this.request = request;
        this.route = route;
    }

    public RequestStarted(String requestId, long startedNanos) {
        this(requestId, startedNanos, null, null);
    }

}
