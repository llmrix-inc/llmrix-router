package com.llmrix.model.router.core.exception;
public final class ModelUnavailableException extends ModelException { public ModelUnavailableException(String message) { super(message, true); } public ModelUnavailableException(String message, Throwable cause) { super(message, cause, true); } }
