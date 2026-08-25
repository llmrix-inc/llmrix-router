package com.llmrix.model.router.spring.boot.http.openai;

import com.llmrix.model.router.core.api.audio.AudioModel;
import com.llmrix.model.router.core.api.audio.AudioResponse;
import com.llmrix.model.router.core.api.audio.AudioTextRequest;
import com.llmrix.model.router.core.api.embedding.EmbeddingResponse;
import com.llmrix.model.router.core.api.embedding.EmbeddingVector;
import com.llmrix.model.router.core.api.image.ImageData;
import com.llmrix.model.router.core.api.image.ImageEditRequest;
import com.llmrix.model.router.core.api.image.ImageModel;
import com.llmrix.model.router.core.api.image.ImageRequest;
import com.llmrix.model.router.core.api.image.ImageResponse;
import com.llmrix.model.router.core.api.video.VideoContent;
import com.llmrix.model.router.core.api.video.VideoModel;
import com.llmrix.model.router.core.api.video.VideoResponse;
import com.llmrix.model.router.core.api.video.VideoLookupRequest;
import com.llmrix.model.router.core.api.video.VideoRemixRequest;
import com.llmrix.model.router.core.api.video.VideoRequest;
import com.llmrix.model.router.core.api.ModelClient;
import com.llmrix.model.router.core.api.audio.SpeechRequest;
import com.llmrix.model.router.core.api.Usage;
import com.llmrix.model.router.core.engine.RoutedModelOperations;
import com.llmrix.model.router.core.engine.RoutedModelOperationsRegistry;
import com.llmrix.model.router.core.model.Capability;
import com.llmrix.model.router.core.model.ModelTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OpenAiMultimodalControllerTest {
    private RoutedModelOperationsRegistry routes;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ModelClient client = ModelClient.builder()
                .embeddings(request -> new EmbeddingResponse(
                        List.of(EmbeddingVector.floats(0, List.of(0.25, 0.75))), "upstream", new Usage(2, 0)))
                .audio(new AudioModel() {
                    @Override public AudioResponse transcribe(AudioTextRequest request) {
                        return jsonAudio("{\"text\":\"transcribed\"}");
                    }
                    @Override public AudioResponse translate(AudioTextRequest request) {
                        return jsonAudio("{\"text\":\"translated\"}");
                    }
                    @Override public AudioResponse speech(SpeechRequest request) {
                        return new AudioResponse(new byte[]{1, 2, 3}, "audio/mpeg", "upstream", Usage.UNKNOWN);
                    }
                })
                .images(new ImageModel() {
                    @Override public ImageResponse generate(ImageRequest request) {
                        return image("https://example.test/generated.png", null);
                    }
                    @Override public ImageResponse edit(ImageEditRequest request) {
                        return image(null, "aW1hZ2U=");
                    }
                })
                .videos(new VideoModel() {
                    @Override public VideoResponse create(VideoRequest request) {
                        return video("video_123", "queued");
                    }
                    @Override public VideoResponse retrieve(VideoLookupRequest request) {
                        return video(request.videoId(), "completed");
                    }
                    @Override public VideoContent content(VideoLookupRequest request) {
                        return new VideoContent(new byte[]{4, 5, 6}, "video/mp4", "upstream", Usage.UNKNOWN);
                    }
                    @Override public VideoResponse delete(VideoLookupRequest request) {
                        return video(request.videoId(), "deleted");
                    }
                    @Override public VideoResponse remix(VideoRemixRequest request) {
                        return video("video_remix", "queued");
                    }
                }).build();
        ModelTarget target = ModelTarget.builder("fake/multimodal", client)
                .capabilities(Capability.EMBEDDINGS, Capability.AUDIO_TRANSCRIPTION,
                        Capability.AUDIO_TRANSLATION, Capability.TEXT_TO_SPEECH,
                        Capability.IMAGE_GENERATION, Capability.IMAGE_EDIT, Capability.VIDEO_GENERATION)
                .build();
        RoutedModelOperations operations = RoutedModelOperations.builder().target(target).build();
        routes = new RoutedModelOperationsRegistry(Map.of("general", operations));
        mvc = MockMvcBuilders.standaloneSetup(
                        new OpenAiEmbeddingController(routes),
                        new OpenAiAudioController(routes),
                        new OpenAiImageController(routes),
                        new OpenAiVideoController(routes))
                .setControllerAdvice(new OpenAiExceptionHandler()).build();
    }

    @AfterEach void close() { routes.close(); }

    @Test
    void servesEmbeddingProtocol() throws Exception {
        mvc.perform(post("/v1/embeddings").contentType("application/json").content("""
                        {"model":"general","input":["hello","world"],"encoding_format":"float"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("list"))
                .andExpect(jsonPath("$.data[0].embedding[1]").value(0.75))
                .andExpect(jsonPath("$.usage.prompt_tokens").value(2));
    }

    @Test
    void servesMultipartAudioAndBinarySpeech() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "sample.wav", "audio/wav", new byte[]{9, 8, 7});
        mvc.perform(multipart("/v1/audio/transcriptions").file(file)
                        .param("model", "general").param("response_format", "json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().json("{\"text\":\"transcribed\"}"));

        mvc.perform(post("/v1/audio/speech").contentType("application/json").content("""
                        {"model":"general","input":"hello","voice":"alloy","response_format":"mp3"}
                        """))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("audio/mpeg"))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
    }

    @Test
    void servesImageGenerationAndMultipartEdit() throws Exception {
        mvc.perform(post("/v1/images/generations").contentType("application/json").content("""
                        {"model":"general","prompt":"draw","n":1}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].url").value("https://example.test/generated.png"));

        MockMultipartFile image = new MockMultipartFile(
                "image", "input.png", "image/png", new byte[]{1, 2});
        mvc.perform(multipart("/v1/images/edits").file(image)
                        .param("model", "general").param("prompt", "edit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].b64_json").value("aW1hZ2U="));
    }

    @Test
    void servesVideoLifecycleAndContent() throws Exception {
        mvc.perform(post("/v1/videos").contentType("application/json").content("""
                        {"model":"general","prompt":"ocean at sunset","seconds":"8","size":"1280x720"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("video_123"))
                .andExpect(jsonPath("$.status").value("queued"));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/v1/videos/video_123").param("model", "general"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/v1/videos/video_123/content").param("model", "general"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("video/mp4"))
                .andExpect(content().bytes(new byte[]{4, 5, 6}));
    }

    private static AudioResponse jsonAudio(String value) {
        return new AudioResponse(value.getBytes(StandardCharsets.UTF_8),
                "application/json", "upstream", Usage.UNKNOWN);
    }

    private static ImageResponse image(String url, String base64) {
        return new ImageResponse(123, List.of(new ImageData(url, base64, null)), "upstream", Usage.UNKNOWN);
    }

    private static VideoResponse video(String id, String status) {
        return new VideoResponse(id, "video", status, "sora", 123L, null, null, 0,
                null, "upstream", Usage.UNKNOWN);
    }
}
