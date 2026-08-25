package com.llmrix.model.router.core.exception;

public final class ModelUnavailableException extends ModelException {
    public ModelUnavailableException(String message) {
        super(message, true);
    }

    public ModelUnavailableException(String message, boolean retryable) {
        super(message, retryable);
    }

    public ModelUnavailableException(String message, Throwable cause) {
        super(message, cause, true);
        copyStatus(cause);
    }

    public ModelUnavailableException(String message, Throwable cause, boolean retryable) {
        super(message, cause, retryable);
        copyStatus(cause);
    }

    private void copyStatus(Throwable cause) {
        if (cause instanceof ModelException failure && failure.statusCode() >= 100) {
            statusCode(failure.statusCode());
        }
    }
}
