package com.llmrix.model.router.core.exception;

public final class RateLimitException extends ModelException {
    public RateLimitException(String message) {
        super(message, true);
    }
}
