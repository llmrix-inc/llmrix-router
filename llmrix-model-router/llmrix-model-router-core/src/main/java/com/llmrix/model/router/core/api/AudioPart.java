package com.llmrix.model.router.core.api;

public record AudioPart(String data, String format) implements ContentPart {
    public AudioPart {
        if (data == null || data.isBlank()) throw new IllegalArgumentException("audio data must not be blank");
        if (format == null || format.isBlank()) throw new IllegalArgumentException("audio format must not be blank");
    }
}
