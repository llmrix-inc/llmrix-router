package com.llmrix.model.router.core.api.chat;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@EqualsAndHashCode
@Accessors(fluent = true)
public final class AudioPart implements ContentPart {
    private final String data;
    private final String format;

    public AudioPart(String data, String format) {
        if (data == null || data.isBlank()) throw new IllegalArgumentException("audio data must not be blank");
        if (format == null || format.isBlank()) throw new IllegalArgumentException("audio format must not be blank");
        this.data = data;
        this.format = format;
    }

}
