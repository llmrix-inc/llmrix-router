package com.llmrix.model.router.spring.boot.http.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.llmrix.model.router.core.api.audio.AudioInput;
import com.llmrix.model.router.core.api.audio.AudioResponse;
import com.llmrix.model.router.core.api.audio.AudioTextRequest;
import com.llmrix.model.router.core.api.audio.SpeechRequest;
import com.llmrix.model.router.core.engine.RoutedModelOperations;
import com.llmrix.model.router.core.engine.RoutedModelOperationsRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/v1/audio")
@ConditionalOnProperty(prefix = "llmrix.model.router.http", name = "enabled", havingValue = "true")
public final class OpenAiAudioController {

    private final OpenAiRoutingContext routing;

    @Autowired
    public OpenAiAudioController(ObjectProvider<RoutedModelOperationsRegistry> routes) {
        this(routes.getIfAvailable());
    }

    OpenAiAudioController(RoutedModelOperationsRegistry routes) {
        this.routing = new OpenAiRoutingContext(routes);
    }

    @PostMapping(value = "/transcriptions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> transcriptions(
            @RequestPart("file") MultipartFile file, @RequestParam String model,
            @RequestParam(required = false) String language,
            @RequestParam(required = false) String prompt,
            @RequestParam(name = "response_format", required = false) String responseFormat,
            @RequestParam(required = false) Double temperature,
            @RequestParam(name = "timestamp_granularities[]", required = false) List<String> timestamps,
            HttpServletRequest servletRequest) throws IOException {
        AudioTextRequest request = audioRequest(file, language, prompt, responseFormat,
                temperature, timestamps, servletRequest);
        return response(routing.route(model).transcribe(request));
    }

    @PostMapping(value = "/translations", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> translations(
            @RequestPart("file") MultipartFile file, @RequestParam String model,
            @RequestParam(required = false) String prompt,
            @RequestParam(name = "response_format", required = false) String responseFormat,
            @RequestParam(required = false) Double temperature,
            HttpServletRequest servletRequest) throws IOException {
        AudioTextRequest request = audioRequest(file, null, prompt, responseFormat,
                temperature, List.of(), servletRequest);
        return response(routing.route(model).translate(request));
    }

    @PostMapping(value = "/speech", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> speech(@RequestBody JsonNode body, HttpServletRequest servletRequest) {
        String model = OpenAiEmbeddingController.text(body, "model", true);
        String input = OpenAiEmbeddingController.text(body, "input", true);
        String voice = OpenAiEmbeddingController.text(body, "voice", true);
        String format = OpenAiEmbeddingController.text(body, "response_format", false);
        Double speed = body.hasNonNull("speed") ? body.get("speed").asDouble() : null;
        String instructions = OpenAiEmbeddingController.text(body, "instructions", false);
        AudioResponse result = routing.route(model).speech(new SpeechRequest(
                input, voice, format, speed, instructions, routing.hints(servletRequest)));
        return response(result);
    }

    private AudioTextRequest audioRequest(MultipartFile file, String language, String prompt,
                                          String responseFormat, Double temperature,
                                          List<String> timestamps, HttpServletRequest servletRequest) throws IOException {
        String filename = file.getOriginalFilename() == null ? "audio" : file.getOriginalFilename();
        AudioInput input = new AudioInput(file.getBytes(), filename, file.getContentType());
        return new AudioTextRequest(input, language, prompt, audioFormat(responseFormat), temperature,
                timestamps, routing.hints(servletRequest));
    }

    private static AudioTextRequest.ResponseFormat audioFormat(String value) {
        if (value == null || value.isBlank()) return AudioTextRequest.ResponseFormat.JSON;
        try {
            return AudioTextRequest.ResponseFormat.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("unsupported response_format: " + value);
        }
    }

    private static ResponseEntity<byte[]> response(AudioResponse response) {
        MediaType type;
        try {
            type = MediaType.parseMediaType(response.mediaType());
        } catch (IllegalArgumentException ignored) {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, type.toString()).body(response.data());
    }
}
