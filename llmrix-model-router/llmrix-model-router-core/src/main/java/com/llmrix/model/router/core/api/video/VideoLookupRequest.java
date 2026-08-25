package com.llmrix.model.router.core.api.video;

import com.llmrix.model.router.core.api.ModelRequest;
import com.llmrix.model.router.core.routing.RoutingHints;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public final class VideoLookupRequest implements ModelRequest {
    private final String videoId;
    private final RoutingHints routingHints;

    public VideoLookupRequest(String videoId, RoutingHints routingHints) {
        if (videoId == null || videoId.isBlank()) throw new IllegalArgumentException("video id must not be blank");
        this.videoId = videoId;
        this.routingHints = routingHints == null ? RoutingHints.none() : routingHints;
    }

    @Override
    public int estimatedInputTokens() {
        return 1;
    }
}
