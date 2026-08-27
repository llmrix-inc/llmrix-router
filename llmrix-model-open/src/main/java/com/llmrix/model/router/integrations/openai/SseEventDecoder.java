package com.llmrix.model.router.integrations.openai;

import java.util.Optional;

final class SseEventDecoder {
    private final StringBuilder data = new StringBuilder();

    Optional<String> accept(String line) {
        if (line == null) return finish();
        if (line.isEmpty()) return finish();
        if (line.startsWith(":")) return Optional.empty();
        if (line.equals("data")) return append("");
        if (line.startsWith("data:")) {
            String value = line.substring(5);
            if (value.startsWith(" ")) value = value.substring(1);
            return append(value);
        }
        return Optional.empty();
    }

    Optional<String> finish() {
        if (data.isEmpty()) return Optional.empty();
        String event = data.toString();
        data.setLength(0);
        return Optional.of(event);
    }

    private Optional<String> append(String value) {
        if (!data.isEmpty()) data.append('\n');
        data.append(value);
        return Optional.empty();
    }
}
