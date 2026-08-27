package com.llmrix.model.router.core.exception;

public final class InvalidRequestException extends ModelException {
    public InvalidRequestException(String message) {
        super(message, false);
    }
}
