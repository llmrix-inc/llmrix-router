package com.llmrix.model.router.spring.boot.http.openai;

import com.fasterxml.jackson.databind.JsonNode;

/** Shared validation helpers for the OpenAI-compatible HTTP adapters. */
final class OpenAiRequestParser {
    private OpenAiRequestParser() {
    }

    static String text(JsonNode body, String name, boolean required) {
        JsonNode value = body == null ? null : body.get(name);
        String result = value == null || value.isNull() ? null : value.asText();
        if (required && (result == null || result.isBlank())) {
            throw new IllegalArgumentException(name + " is required");
        }
        return result;
    }

    static Integer integer(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException("expected integer");
        }
        return value.intValue();
    }
}
