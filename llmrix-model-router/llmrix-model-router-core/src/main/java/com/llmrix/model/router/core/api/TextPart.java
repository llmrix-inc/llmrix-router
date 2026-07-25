package com.llmrix.model.router.core.api;

import java.util.Objects;

public record TextPart(String text) implements ContentPart {
    public TextPart { Objects.requireNonNull(text, "text"); }
}
