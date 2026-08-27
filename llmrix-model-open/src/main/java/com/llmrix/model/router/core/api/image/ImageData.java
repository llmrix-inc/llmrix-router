package com.llmrix.model.router.core.api.image;

import lombok.Value;
import lombok.experimental.Accessors;

@Value
@Accessors(fluent = true)
public class ImageData {
    String url;
    String base64;
    String revisedPrompt;
}
