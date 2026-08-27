package com.llmrix.model.router.core.api.audio;

import com.llmrix.model.router.core.api.RoutedResponse;
import com.llmrix.model.router.core.api.Usage;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.Arrays;

@Getter
@EqualsAndHashCode
@Accessors(fluent = true)
public final class AudioResponse implements RoutedResponse<AudioResponse> {
    private final byte[] data;
    private final String mediaType;
    private final String modelId;
    private final Usage usage;

    public AudioResponse(byte[] data, String mediaType, String modelId, Usage usage) {
        this.data = data == null ? new byte[0] : Arrays.copyOf(data, data.length);
        this.mediaType = mediaType == null ? "application/octet-stream" : mediaType;
        this.modelId = modelId;
        this.usage = usage == null ? Usage.UNKNOWN : usage;
    }

    public byte[] data() {
        return Arrays.copyOf(data, data.length);
    }

    @Override
    public AudioResponse routedBy(String targetId) {
        return new AudioResponse(data, mediaType, targetId, usage);
    }
}
