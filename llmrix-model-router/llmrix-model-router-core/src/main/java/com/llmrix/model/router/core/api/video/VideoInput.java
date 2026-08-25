package com.llmrix.model.router.core.api.video;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.Arrays;

@Getter
@EqualsAndHashCode
@Accessors(fluent = true)
public final class VideoInput {
    private final byte[] data;
    private final String filename;
    private final String mediaType;

    public VideoInput(byte[] data, String filename, String mediaType) {
        if (data == null || data.length == 0) throw new IllegalArgumentException("video input must not be empty");
        if (filename == null || filename.isBlank()) throw new IllegalArgumentException("video filename must not be blank");
        this.data = Arrays.copyOf(data, data.length);
        this.filename = filename;
        this.mediaType = mediaType == null || mediaType.isBlank() ? "application/octet-stream" : mediaType;
    }

    public byte[] data() {
        return Arrays.copyOf(data, data.length);
    }
}
