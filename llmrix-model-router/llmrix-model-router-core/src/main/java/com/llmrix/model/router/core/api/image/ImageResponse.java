package com.llmrix.model.router.core.api.image;

import com.llmrix.model.router.core.api.RoutedResponse;
import com.llmrix.model.router.core.api.Usage;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.List;

@Getter
@EqualsAndHashCode
@Accessors(fluent = true)
public final class ImageResponse implements RoutedResponse<ImageResponse> {
    private final long created;
    private final List<ImageData> data;
    private final String modelId;
    private final Usage usage;

    public ImageResponse(long created, List<ImageData> data, String modelId, Usage usage) {
        this.created = created;
        this.data = data == null ? List.of() : List.copyOf(data);
        this.modelId = modelId;
        this.usage = usage == null ? Usage.UNKNOWN : usage;
    }

    @Override
    public ImageResponse routedBy(String targetId) {
        return new ImageResponse(created, data, targetId, usage);
    }
}
