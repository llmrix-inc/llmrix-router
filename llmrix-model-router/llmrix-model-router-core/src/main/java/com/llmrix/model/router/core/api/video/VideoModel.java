package com.llmrix.model.router.core.api.video;

public interface VideoModel {
    VideoResponse create(VideoRequest request);

    VideoResponse retrieve(VideoLookupRequest request);

    VideoContent content(VideoLookupRequest request);

    VideoResponse delete(VideoLookupRequest request);

    VideoResponse remix(VideoRemixRequest request);
}
