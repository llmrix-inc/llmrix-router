package com.llmrix.model.router.core.exception;

public final class BudgetExceededException extends ModelException {
    public BudgetExceededException(String message) {
        super(message, false);
    }
}
