package com.llmrix.model.router.core.api.video;

import com.llmrix.model.router.core.api.ModelRequest;
import com.llmrix.model.router.core.routing.RoutingHints;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public final class VideoRemixRequest implements ModelRequest {
    private final String videoId;
    private final String prompt;
    private final RoutingHints routingHints;

    public VideoRemixRequest(String videoId, String prompt, RoutingHints routingHints) {
        if (videoId == null || videoId.isBlank()) throw new IllegalArgumentException("video id must not be blank");
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("video prompt must not be blank");
        this.videoId = videoId;
        this.prompt = prompt;
        this.routingHints = routingHints == null ? RoutingHints.none() : routingHints;
    }

    @Override
    public int estimatedInputTokens() {
        return Math.max(1, (prompt.length() + 3) / 4);
    }
}
