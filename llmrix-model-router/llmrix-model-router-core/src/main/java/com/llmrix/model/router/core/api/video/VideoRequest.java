package com.llmrix.model.router.core.api.video;

import com.llmrix.model.router.core.api.ModelRequest;
import com.llmrix.model.router.core.routing.RoutingHints;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public final class VideoRequest implements ModelRequest {
    private final String prompt;
    private final String seconds;
    private final String size;
    private final String inputReferenceUrl;
    private final VideoInput inputReference;
    private final RoutingHints routingHints;

    public VideoRequest(String prompt, String seconds, String size, String inputReferenceUrl,
                        RoutingHints routingHints) {
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("video prompt must not be blank");
        this.prompt = prompt;
        this.seconds = seconds;
        this.size = size;
        this.inputReferenceUrl = inputReferenceUrl;
        this.inputReference = null;
        this.routingHints = routingHints == null ? RoutingHints.none() : routingHints;
    }

    public VideoRequest(String prompt, String seconds, String size, VideoInput inputReference,
                        RoutingHints routingHints) {
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("video prompt must not be blank");
        this.prompt = prompt;
        this.seconds = seconds;
        this.size = size;
        this.inputReferenceUrl = null;
        this.inputReference = inputReference;
        this.routingHints = routingHints == null ? RoutingHints.none() : routingHints;
    }

    @Override
    public int estimatedInputTokens() {
        return Math.max(1, (prompt.length() + 3) / 4);
    }
}
