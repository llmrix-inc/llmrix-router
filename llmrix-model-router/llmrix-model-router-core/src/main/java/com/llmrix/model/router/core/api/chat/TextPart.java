package com.llmrix.model.router.core.api.chat;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.Objects;

@Getter
@EqualsAndHashCode
@Accessors(fluent = true)
public final class TextPart implements ContentPart {
    private final String text;

    public TextPart(String text) {
        this.text = Objects.requireNonNull(text, "text");
    }

}
