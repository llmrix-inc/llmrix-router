package com.llmrix.model.router.core.api;

import com.llmrix.model.router.core.routing.RoutingHints;

/**
 * Common information required by the routing engine for any model operation.
 */
public interface ModelRequest {
    RoutingHints routingHints();

    int estimatedInputTokens();

    default int estimatedOutputTokens() {
        return 0;
    }
}
