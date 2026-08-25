package com.llmrix.model.router.core.api.image;

import com.llmrix.model.router.core.api.ModelRequest;
import com.llmrix.model.router.core.routing.RoutingHints;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public class ImageRequest implements ModelRequest {
    private final String prompt;
    private final Integer count;
    private final String size;
    private final String quality;
    private final String style;
    private final String responseFormat;
    private final String user;
    private final String background;
    private final String outputFormat;
    private final Integer outputCompression;
    private final RoutingHints routingHints;

    public ImageRequest(String prompt, Integer count, String size, String quality, String style,
                        String responseFormat, String user, String background, String outputFormat,
                        Integer outputCompression, RoutingHints routingHints) {
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("image prompt must not be blank");
        if (count != null && count < 1) throw new IllegalArgumentException("image count must be > 0");
        if (outputCompression != null && (outputCompression < 0 || outputCompression > 100)) {
            throw new IllegalArgumentException("output compression must be between 0 and 100");
        }
        this.prompt = prompt;
        this.count = count;
        this.size = size;
        this.quality = quality;
        this.style = style;
        this.responseFormat = responseFormat;
        this.user = user;
        this.background = background;
        this.outputFormat = outputFormat;
        this.outputCompression = outputCompression;
        this.routingHints = routingHints == null ? RoutingHints.none() : routingHints;
    }

    @Override
    public int estimatedInputTokens() {
        return Math.max(1, (prompt.length() + 3) / 4);
    }
}
