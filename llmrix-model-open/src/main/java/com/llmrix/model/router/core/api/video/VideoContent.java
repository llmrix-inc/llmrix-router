package com.llmrix.model.router.core.api.video;

import com.llmrix.model.router.core.api.RoutedResponse;
import com.llmrix.model.router.core.api.Usage;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@EqualsAndHashCode
@Accessors(fluent = true)
public final class VideoContent implements RoutedResponse<VideoContent> {
    private final byte[] data;
    private final String mediaType;
    private final String modelId;
    private final Usage usage;

    public VideoContent(byte[] data, String mediaType, String modelId, Usage usage) {
        if (data == null) throw new IllegalArgumentException("video content must not be null");
        this.data = java.util.Arrays.copyOf(data, data.length);
        this.mediaType = mediaType == null ? "video/mp4" : mediaType;
        this.modelId = modelId;
        this.usage = usage == null ? Usage.UNKNOWN : usage;
    }

    public byte[] data() {
        return java.util.Arrays.copyOf(data, data.length);
    }

    @Override
    public VideoContent routedBy(String targetId) {
        return new VideoContent(data, mediaType, targetId, usage);
    }
}
