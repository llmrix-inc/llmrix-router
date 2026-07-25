package com.llmrix.model.router.core.api;

public record ImagePart(String url, String detail) implements ContentPart {
    public ImagePart {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("image url must not be blank");
        if (detail != null && !detail.equals("auto") && !detail.equals("low") && !detail.equals("high")) {
            throw new IllegalArgumentException("image detail must be auto, low or high");
        }
    }

    public ImagePart(String url) { this(url, null); }
}
