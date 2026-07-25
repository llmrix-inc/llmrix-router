package com.llmrix.model.orion.client;

/** A transport or protocol failure returned by a remote LLM Router server. */
public final class OrionModelClientException extends RuntimeException {
    private final int statusCode;

    public OrionModelClientException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public OrionModelClientException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
    }

    public int statusCode() {
        return statusCode;
    }
}
