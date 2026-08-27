package com.llmrix.model.router.spring.boot.http.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.llmrix.model.router.core.api.video.VideoContent;
import com.llmrix.model.router.core.api.video.VideoInput;
import com.llmrix.model.router.core.api.video.VideoLookupRequest;
import com.llmrix.model.router.core.api.video.VideoRemixRequest;
import com.llmrix.model.router.core.api.video.VideoRequest;
import com.llmrix.model.router.core.api.video.VideoResponse;
import com.llmrix.model.router.core.engine.RoutedModelOperations;
import com.llmrix.model.router.core.engine.RoutedModelOperationsRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.io.IOException;

@RestController
@RequestMapping("/v1/videos")
@ConditionalOnProperty(prefix = "llmrix.model.router.http", name = "enabled", havingValue = "true")
public final class OpenAiVideoController {
    private final OpenAiRoutingContext routing;

    @Autowired
    public OpenAiVideoController(ObjectProvider<RoutedModelOperationsRegistry> routes) {
        this(routes.getIfAvailable());
    }

    OpenAiVideoController(RoutedModelOperationsRegistry routes) {
        this.routing = new OpenAiRoutingContext(routes);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> create(@RequestBody JsonNode body, HttpServletRequest servletRequest) {
        String route = OpenAiRequestParser.text(body, "model", true);
        VideoRequest request = new VideoRequest(
                OpenAiRequestParser.text(body, "prompt", true), OpenAiRequestParser.text(body, "seconds", false),
                OpenAiRequestParser.text(body, "size", false), OpenAiRequestParser.text(body, "input_reference", false),
                routing.hints(servletRequest));
        return protocol(routing.route(route).create(request));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> createMultipart(
            @RequestParam String model, @RequestParam String prompt,
            @RequestParam(required = false) String seconds, @RequestParam(required = false) String size,
            @RequestPart(value = "input_reference", required = false) MultipartFile inputReference,
            HttpServletRequest servletRequest) throws IOException {
        VideoInput input = inputReference == null ? null : new VideoInput(
                inputReference.getBytes(),
                inputReference.getOriginalFilename() == null ? "input_reference" : inputReference.getOriginalFilename(),
                inputReference.getContentType());
        VideoRequest request = new VideoRequest(prompt, seconds, size, input, routing.hints(servletRequest));
        return protocol(routing.route(model).create(request));
    }

    @GetMapping("/{videoId}")
    public Map<String, Object> retrieve(@PathVariable String videoId,
                                        @RequestParam(required = false) String model,
                                        HttpServletRequest servletRequest) {
        return protocol(routing.routeOrDefault(model).retrieve(
                new VideoLookupRequest(videoId, routing.hints(servletRequest))));
    }

    @GetMapping(value = "/{videoId}/content", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> content(@PathVariable String videoId,
                                          @RequestParam(required = false) String model,
                                          HttpServletRequest servletRequest) {
        VideoContent content = routing.routeOrDefault(model).content(
                new VideoLookupRequest(videoId, routing.hints(servletRequest)));
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, content.mediaType()).body(content.data());
    }

    @DeleteMapping("/{videoId}")
    public Map<String, Object> delete(@PathVariable String videoId,
                                      @RequestParam(required = false) String model,
                                      HttpServletRequest servletRequest) {
        return protocol(routing.routeOrDefault(model).delete(
                new VideoLookupRequest(videoId, routing.hints(servletRequest))));
    }

    @PostMapping("/{videoId}/remix")
    public Map<String, Object> remix(@PathVariable String videoId, @RequestBody JsonNode body,
                                     @RequestParam(required = false) String model,
                                     HttpServletRequest servletRequest) {
        return protocol(routing.routeOrDefault(model).remix(new VideoRemixRequest(
                videoId, OpenAiRequestParser.text(body, "prompt", true), routing.hints(servletRequest))));
    }

    private static Map<String, Object> protocol(VideoResponse response) {
        Map<String, Object> value = new LinkedHashMap<>();
        put(value, "id", response.id());
        put(value, "object", response.object());
        put(value, "status", response.status());
        put(value, "model", response.model());
        put(value, "created_at", response.createdAt());
        put(value, "completed_at", response.completedAt());
        put(value, "expires_at", response.expiresAt());
        put(value, "progress", response.progress());
        put(value, "error", response.error());
        if (response.usage().inputTokens() >= 0 && response.usage().outputTokens() >= 0) {
            value.put("usage", Map.of("input_tokens", response.usage().inputTokens(),
                    "output_tokens", response.usage().outputTokens(),
                    "total_tokens", response.usage().totalTokens()));
        }
        return value;
    }

    private static void put(Map<String, Object> value, String key, Object item) {
        if (item != null) value.put(key, item);
    }
}
