package com.llmrix.model.router.core.api.chat;

import lombok.Value;
import lombok.experimental.Accessors;

@Value
@Accessors(fluent = true)
public class StreamOptions {
    public static final StreamOptions DEFAULT = new StreamOptions(true);

    boolean includeUsage;
}
