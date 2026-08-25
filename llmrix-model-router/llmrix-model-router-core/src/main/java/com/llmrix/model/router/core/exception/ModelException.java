package com.llmrix.model.router.core.exception;

public class ModelException extends RuntimeException {
    private final boolean retryable;
    private int statusCode = -1;

    public ModelException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public ModelException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }

    public int statusCode() {
        return statusCode;
    }

    /**
     * Adds transport context without changing the stable exception subtype.
     */
    public ModelException statusCode(int value) {
        if (value < 100 || value > 599) throw new IllegalArgumentException("statusCode must be a valid HTTP status");
        this.statusCode = value;
        return this;
    }
}
