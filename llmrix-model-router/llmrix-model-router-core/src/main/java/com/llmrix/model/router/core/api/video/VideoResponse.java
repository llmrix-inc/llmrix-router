package com.llmrix.model.router.core.api.video;

import com.llmrix.model.router.core.api.RoutedResponse;
import com.llmrix.model.router.core.api.Usage;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@EqualsAndHashCode
@Accessors(fluent = true)
public final class VideoResponse implements RoutedResponse<VideoResponse> {
    private final String id;
    private final String object;
    private final String status;
    private final String model;
    private final Long createdAt;
    private final Long completedAt;
    private final Long expiresAt;
    private final Integer progress;
    private final String error;
    private final String modelId;
    private final Usage usage;

    public VideoResponse(String id, String object, String status, String model, Long createdAt,
                         Long completedAt, Long expiresAt, Integer progress, String error,
                         String modelId, Usage usage) {
        this.id = id;
        this.object = object;
        this.status = status;
        this.model = model;
        this.createdAt = createdAt;
        this.completedAt = completedAt;
        this.expiresAt = expiresAt;
        this.progress = progress;
        this.error = error;
        this.modelId = modelId;
        this.usage = usage == null ? Usage.UNKNOWN : usage;
    }

    @Override
    public VideoResponse routedBy(String targetId) {
        return new VideoResponse(id, object, status, model, createdAt, completedAt, expiresAt,
                progress, error, targetId, usage);
    }
}
