package com.llmrix.model.router.core.api.image;

public interface ImageModel {
    ImageResponse generate(ImageRequest request);

    ImageResponse edit(ImageEditRequest request);
}
