package com.llmrix.model.router.core.exception;

/** Raised when a request references a route that is not configured. */
public final class UnknownRouteException extends RuntimeException {
    private final String routeId;

    public UnknownRouteException(String routeId) {
        super("unknown route: " + routeId);
        this.routeId = routeId;
    }

    public String routeId() {
        return routeId;
    }
}
