package com.llmrix.model.router.spring.boot.observability;

@FunctionalInterface
public interface PromptSanitizer {
    String sanitize(String prompt);
}
