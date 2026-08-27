package com.llmrix.model.router.spring.boot.http.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.llmrix.model.router.core.api.image.ImageData;
import com.llmrix.model.router.core.api.image.ImageEditRequest;
import com.llmrix.model.router.core.api.image.ImageInput;
import com.llmrix.model.router.core.api.image.ImageRequest;
import com.llmrix.model.router.core.api.image.ImageResponse;
import com.llmrix.model.router.core.engine.RoutedModelOperationsRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/images")
@ConditionalOnProperty(prefix = "llmrix.model.router.http", name = "enabled", havingValue = "true")
public final class OpenAiImageController {

    private final OpenAiRoutingContext routing;

    @Autowired
    public OpenAiImageController(ObjectProvider<RoutedModelOperationsRegistry> routes) {
        this(routes.getIfAvailable());
    }

    OpenAiImageController(RoutedModelOperationsRegistry routes) {
        routing = new OpenAiRoutingContext(routes);
    }

    @PostMapping("/generations")
    public Map<String, Object> generations(@RequestBody JsonNode body, HttpServletRequest servletRequest) {
        String model = OpenAiRequestParser.text(body, "model", true);
        ImageRequest request = new ImageRequest(
                OpenAiRequestParser.text(body, "prompt", true),
                OpenAiRequestParser.integer(body.get("n")),
                OpenAiRequestParser.text(body, "size", false),
                OpenAiRequestParser.text(body, "quality", false),
                OpenAiRequestParser.text(body, "style", false),
                OpenAiRequestParser.text(body, "response_format", false),
                OpenAiRequestParser.text(body, "user", false),
                OpenAiRequestParser.text(body, "background", false),
                OpenAiRequestParser.text(body, "output_format", false),
                OpenAiRequestParser.integer(body.get("output_compression")),
                routing.hints(servletRequest));
        return protocol(routing.route(model).generate(request));
    }

    @PostMapping(value = "/edits", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> edits(
            @RequestPart(value = "image", required = false) List<MultipartFile> images,
            @RequestPart(value = "image[]", required = false) List<MultipartFile> bracketedImages,
            @RequestPart(value = "mask", required = false) MultipartFile mask,
            @RequestParam String model, @RequestParam String prompt,
            @RequestParam(required = false) Integer n,
            @RequestParam(required = false) String size,
            @RequestParam(required = false) String quality,
            @RequestParam(name = "response_format", required = false) String responseFormat,
            @RequestParam(required = false) String user,
            @RequestParam(required = false) String background,
            @RequestParam(name = "output_format", required = false) String outputFormat,
            @RequestParam(name = "output_compression", required = false) Integer outputCompression,
            HttpServletRequest servletRequest) throws IOException {
        List<ImageInput> inputs = new ArrayList<>();
        if (images != null) for (MultipartFile image : images) inputs.add(input(image, "image"));
        if (bracketedImages != null) {
            for (MultipartFile image : bracketedImages) inputs.add(input(image, "image"));
        }
        ImageEditRequest request = new ImageEditRequest(inputs, mask == null ? null : input(mask, "mask"),
                prompt, n, size, quality, responseFormat, user, background, outputFormat,
                outputCompression, routing.hints(servletRequest));
        return protocol(routing.route(model).edit(request));
    }

    private static ImageInput input(MultipartFile file, String fallbackName) throws IOException {
        String filename = file.getOriginalFilename() == null ? fallbackName : file.getOriginalFilename();
        return new ImageInput(file.getBytes(), filename, file.getContentType());
    }

    private static Map<String, Object> protocol(ImageResponse response) {
        List<Map<String, Object>> data = response.data().stream().map(OpenAiImageController::protocol).toList();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("created", response.created());
        value.put("data", data);
        if (response.modelId() != null) value.put("model", response.modelId());
        if (response.usage().inputTokens() >= 0 && response.usage().outputTokens() >= 0) {
            value.put("usage", Map.of("input_tokens", response.usage().inputTokens(),
                    "output_tokens", response.usage().outputTokens(),
                    "total_tokens", response.usage().totalTokens()));
        }
        return value;
    }

    private static Map<String, Object> protocol(ImageData image) {
        Map<String, Object> value = new LinkedHashMap<>();
        if (image.url() != null) value.put("url", image.url());
        if (image.base64() != null) value.put("b64_json", image.base64());
        if (image.revisedPrompt() != null) value.put("revised_prompt", image.revisedPrompt());
        return value;
    }
}
