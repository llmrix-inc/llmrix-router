package com.llmrix.model.router.core.api.chat;

import java.util.Objects;

/** Provider-neutral prompt cache hints. Providers may ignore unsupported hints. */
public final class PromptCacheOptions {
    private final String key;
    private final String retention;

    public PromptCacheOptions(String key, String retention) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("prompt cache key must not be blank");
        if (retention != null && retention.isBlank()) throw new IllegalArgumentException("prompt cache retention must not be blank");
        this.key = key;
        this.retention = retention;
    }

    public String key() { return key; }
    public String retention() { return retention; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof PromptCacheOptions that)) return false;
        return Objects.equals(key, that.key) && Objects.equals(retention, that.retention);
    }
    @Override public int hashCode() { return Objects.hash(key, retention); }
    @Override public String toString() { return "PromptCacheOptions[key=" + key + ", retention=" + retention + "]"; }
}
