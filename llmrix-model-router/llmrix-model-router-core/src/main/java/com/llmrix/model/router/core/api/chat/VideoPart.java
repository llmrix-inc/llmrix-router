package com.llmrix.model.router.core.api.chat;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@EqualsAndHashCode
@Accessors(fluent = true)
public final class VideoPart implements ContentPart {
    private final String url;

    public VideoPart(String url) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("video url must not be blank");
        this.url = url;
    }
}
