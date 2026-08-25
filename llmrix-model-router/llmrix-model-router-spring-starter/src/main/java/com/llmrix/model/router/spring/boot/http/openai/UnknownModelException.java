package com.llmrix.model.router.spring.boot.http.openai;

final class UnknownModelException extends RuntimeException {
    private final String model;

    UnknownModelException(String model) {
        super("The model '" + model + "' does not exist");
        this.model = model;
    }

    String model() {
        return model;
    }
}
