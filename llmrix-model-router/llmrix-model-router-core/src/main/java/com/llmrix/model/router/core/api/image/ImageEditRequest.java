package com.llmrix.model.router.core.api.image;

import com.llmrix.model.router.core.routing.RoutingHints;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.List;

@Getter
@Accessors(fluent = true)
public final class ImageEditRequest extends ImageRequest {
    private final List<ImageInput> images;
    private final ImageInput mask;

    public ImageEditRequest(List<ImageInput> images, ImageInput mask, String prompt, Integer count,
                            String size, String quality, String responseFormat, String user,
                            String background, String outputFormat, Integer outputCompression,
                            RoutingHints routingHints) {
        super(prompt, count, size, quality, null, responseFormat, user, background,
                outputFormat, outputCompression, routingHints);
        if (images == null || images.isEmpty()) throw new IllegalArgumentException("at least one image is required");
        this.images = List.copyOf(images);
        this.mask = mask;
    }
}
