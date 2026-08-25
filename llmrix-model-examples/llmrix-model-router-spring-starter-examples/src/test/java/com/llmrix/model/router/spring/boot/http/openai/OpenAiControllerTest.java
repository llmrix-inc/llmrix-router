package com.llmrix.model.router.spring.boot.http.openai;

import com.llmrix.model.router.spring.boot.http.web.RequestIdFilter;

import com.llmrix.model.router.core.api.chat.ChatResponse;
import com.llmrix.model.router.core.engine.RoutedChatModel;
import com.llmrix.model.router.core.engine.RoutedChatModels;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmrix.model.router.core.api.chat.ChatRequest;
import com.llmrix.model.router.core.api.chat.ImagePart;
import com.llmrix.model.router.core.api.chat.AudioPart;
import com.llmrix.model.router.core.api.chat.VideoPart;
import com.llmrix.model.router.core.api.chat.FilePart;
import com.llmrix.model.router.core.model.Capability;
import com.llmrix.model.router.core.api.chat.ResponseFormat;
import com.llmrix.model.router.core.api.chat.ToolCallPart;
import com.llmrix.model.router.core.api.chat.ToolChoice;
import com.llmrix.model.router.core.api.chat.ToolResultPart;
import com.llmrix.model.router.core.api.Usage;
import com.llmrix.model.router.core.api.chat.ChatChunk;
import com.llmrix.model.router.core.api.chat.ChatModel;
import java.util.concurrent.Flow;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

class OpenAiControllerTest {
    private final RoutedChatModel model = RoutedChatModel.builder()
            .target("fake", request -> ChatResponse.of("hello"))
            .build();
    private final RoutedChatModels models = new RoutedChatModels(Map.of("general", model));

    @AfterEach
    void close() {
        models.close();
    }

    @Test
    void returnsOpenAiCompatibleCompletion() {
        OpenAiController controller = new OpenAiController(models);
        Object result = controller.chat(new OpenAiController.CompletionRequest(
                "general", List.of(new OpenAiController.CompletionMessage("user", "hi")), false));

        assertThat(result).isInstanceOf(Map.class);
        Map<?, ?> response = (Map<?, ?>) result;
        assertThat(response.get("object")).isEqualTo("chat.completion");
        assertThat(response.get("model")).isEqualTo("general");
    }

    @Test
    void propagatesRequestIdIntoRoutingHints() throws Exception {
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        try (RoutedChatModel route = RoutedChatModel.builder()
                .target("capture", request -> { captured.set(request); return ChatResponse.of("ok"); }).build();
             RoutedChatModels localModels = new RoutedChatModels(Map.of("general", route))) {
            MockMvcBuilders.standaloneSetup(new OpenAiController(localModels))
                    .addFilters(new RequestIdFilter()).build()
                    .perform(post("/v1/chat/completions")
                            .header("X-Request-Id", "req-propagated")
                            .contentType("application/json")
                            .content("{\"model\":\"general\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                    .andExpect(header().string("X-Request-Id", "req-propagated"));

            assertThat(captured.get().routingHints().attributes())
                    .containsEntry(com.llmrix.model.router.core.routing.RoutingHints.REQUEST_ID, "req-propagated");
        }
    }

    @Test
    void listsConfiguredRoutesAsModels() {
        OpenAiController controller = new OpenAiController(models);

        assertThat(controller.models().toString()).contains("general");
    }

    @Test
    void rejectsUnknownModelWithDedicatedException() {
        OpenAiController controller = new OpenAiController(models);

        assertThatThrownBy(() -> controller.chat(new OpenAiController.CompletionRequest(
                "missing", List.of(new OpenAiController.CompletionMessage("user", "hi")), false)))
                .isInstanceOf(UnknownModelException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void acceptsOpenAiMultimodalContentArray() throws Exception {
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        try (RoutedChatModel route = RoutedChatModel.builder()
                .target("capture", request -> {
                    captured.set(request);
                    return ChatResponse.of("ok");
                }, target -> target.capabilities(Capability.CHAT, Capability.VIDEO_INPUT,
                        Capability.FILE_INPUT, Capability.AUDIO_INPUT)).build();
             RoutedChatModels localModels = new RoutedChatModels(Map.of("vision", route))) {
            OpenAiController controller = new OpenAiController(localModels);
            var content = new ObjectMapper().readTree("""
                    [
                      {"type":"text","text":"describe"},
                      {"type":"image_url","image_url":{"url":"https://example.test/image.png","detail":"high"}},
                      {"type":"input_audio","input_audio":{"data":"ZmFrZQ==","format":"wav"}},
                      {"type":"video_url","video_url":{"url":"https://vjs.zencdn.net/v/oceans.mp4"}},
                      {"type":"file","file":{"filename":"document.pdf","file_url":"https://example.test/document.pdf"}}
                    ]
                    """);

            controller.chat(new OpenAiController.CompletionRequest(
                    "vision", List.of(new OpenAiController.CompletionMessage("user", content)), false));

            assertThat(captured.get().messages().get(0).contents())
                    .anyMatch(ImagePart.class::isInstance)
                    .anyMatch(AudioPart.class::isInstance)
                    .anyMatch(VideoPart.class::isInstance)
                    .anyMatch(FilePart.class::isInstance);
        }
    }

    @Test
    void acceptsOpenAiJsonSchemaResponseFormat() throws Exception {
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        try (RoutedChatModel route = RoutedChatModel.builder()
                .target("capture", request -> {
                    captured.set(request);
                    return ChatResponse.of("{\"answer\":\"ok\"}");
                }).build();
             RoutedChatModels localModels = new RoutedChatModels(Map.of("json", route))) {
            OpenAiController controller = new OpenAiController(localModels);
            OpenAiController.CompletionRequest request = new ObjectMapper().readValue("""
                    {
                      "model":"json",
                      "messages":[{"role":"user","content":"answer as json"}],
                      "response_format":{"type":"json_schema","json_schema":{
                        "name":"answer","strict":true,
                        "schema":{"type":"object","properties":{"answer":{"type":"string"}}}
                      }}
                    }
                    """, OpenAiController.CompletionRequest.class);

            controller.chat(request);

            assertThat(captured.get().responseFormat().type()).isEqualTo(ResponseFormat.Type.JSON_SCHEMA);
            assertThat(captured.get().responseFormat().name()).isEqualTo("answer");
            assertThat(captured.get().responseFormat().strict()).isTrue();
        }
    }

    @Test
    void acceptsRequestLevelStreamOptions() throws Exception {
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        try (RoutedChatModel route = RoutedChatModel.builder()
                .target("capture", request -> { captured.set(request); return ChatResponse.of("ok"); })
                .build();
             RoutedChatModels localModels = new RoutedChatModels(Map.of("general", route))) {
            OpenAiController.CompletionRequest request = new ObjectMapper().readValue("""
                    {"model":"general","messages":[{"role":"user","content":"hi"}],
                     "stream_options":{"include_usage":false}}
                    """, OpenAiController.CompletionRequest.class);

            new OpenAiController(localModels).chat(request);

            assertThat(captured.get().streamOptions().includeUsage()).isFalse();
        }
    }

    @Test
    void returnsOpenAiResponsesApiShape() {
        OpenAiController controller = new OpenAiController(models);

        Object raw = controller.responses(new OpenAiController.ResponsesRequest(
                "general", com.fasterxml.jackson.databind.node.TextNode.valueOf("hello"), false, null, null));
        assertThat(raw).isInstanceOf(Map.class);
        Map<?, ?> response = (Map<?, ?>) raw;

        assertThat(response.get("object")).isEqualTo("response");
        assertThat(response.get("status")).isEqualTo("completed");
        assertThat(response.toString()).contains("output_text", "hello");
    }

    @Test
    void rejectsUnsupportedResponsesFieldsExplicitly() throws Exception {
        OpenAiController.ResponsesRequest request = new ObjectMapper().readValue("""
                {"model":"general","input":"hello","instructions":"be concise","store":false}
                """, OpenAiController.ResponsesRequest.class);

        assertThatThrownBy(() -> new OpenAiController(models).responses(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported Responses API fields")
                .hasMessageContaining("instructions", "store");
    }

    @Test
    void acceptsResponsesMultimodalInput() throws Exception {
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        try (RoutedChatModel route = RoutedChatModel.builder()
                .target("capture", request -> { captured.set(request); return ChatResponse.of("ok"); }).build();
             RoutedChatModels localModels = new RoutedChatModels(Map.of("vision", route))) {
            var input = new ObjectMapper().readTree("""
                    [{"role":"user","content":[
                      {"type":"input_text","text":"describe"},
                      {"type":"input_image","image_url":"https://example.test/image.png","detail":"high"}
                    ]}]
                    """);

            new OpenAiController(localModels).responses(
                    new OpenAiController.ResponsesRequest("vision", input, false, null, null));

            assertThat(captured.get().messages().get(0).contents()).anyMatch(ImagePart.class::isInstance);
        }
    }

    @Test
    void mapsResponsesFunctionToolsAndPriorOutputs() throws Exception {
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        try (RoutedChatModel route = RoutedChatModel.builder()
                .target("capture", request -> { captured.set(request); return ChatResponse.of("ok"); }).build();
             RoutedChatModels localModels = new RoutedChatModels(Map.of("tools", route))) {
            ObjectMapper mapper = new ObjectMapper();
            var input = mapper.readTree("""
                    [
                      {"role":"user","content":"continue"},
                      {"type":"function_call","call_id":"call_1","name":"weather","arguments":"{\\"city\\":\\"Beijing\\"}"},
                      {"type":"function_call_output","call_id":"call_1","output":"{\\"temperature\\":25}"}
                    ]
                    """);
            var tool = mapper.readTree("""
                    {"type":"function","name":"weather","description":"Get weather",
                     "parameters":{"type":"object"},"strict":true}
                    """);
            var choice = mapper.readTree("{\"type\":\"function\",\"name\":\"weather\"}");

            new OpenAiController(localModels).responses(new OpenAiController.ResponsesRequest(
                    "tools", input, false, null, null, List.of(tool), choice));

            ChatRequest request = captured.get();
            assertThat(request.tools()).singleElement().satisfies(definition -> {
                assertThat(definition.name()).isEqualTo("weather");
                assertThat(definition.strict()).isTrue();
                assertThat(definition.parameters()).containsEntry("type", "object");
            });
            assertThat(request.toolChoice()).isEqualTo(ToolChoice.named("weather"));
            assertThat(request.messages().get(1).contents()).singleElement().isInstanceOf(ToolCallPart.class);
            assertThat(request.messages().get(2).contents()).singleElement().isInstanceOf(ToolResultPart.class);
        }
    }

    @Test
    void returnsResponsesFunctionCallOutput() {
        try (RoutedChatModel route = RoutedChatModel.builder()
                .target("tool", request -> new ChatResponse("", "tool", Usage.UNKNOWN, Map.of(),
                        List.of(new ToolCallPart("call_2", "weather", "{\"city\":\"Shanghai\"}"))))
                .build(); RoutedChatModels localModels = new RoutedChatModels(Map.of("tools", route))) {
            Object raw = new OpenAiController(localModels).responses(new OpenAiController.ResponsesRequest(
                    "tools", com.fasterxml.jackson.databind.node.TextNode.valueOf("weather"),
                    false, null, null));

            assertThat(raw.toString()).contains("function_call", "call_2", "weather", "Shanghai");
        }
    }

    @Test
    void streamsCompleteResponsesTextLifecycleWithoutNetworkPort() throws Exception {
        ChatModel streaming = new ChatModel() {
            @Override public ChatResponse chat(ChatRequest request) { return ChatResponse.of("hello"); }
            @Override public Flow.Publisher<ChatChunk> stream(ChatRequest request) {
                return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                    private int stage;
                    @Override public void request(long n) {
                        while (n-- > 0 && stage < 2) {
                            if (stage++ == 0) {
                                subscriber.onNext(new ChatChunk("hello", false, Usage.UNKNOWN));
                            } else {
                                subscriber.onNext(new ChatChunk("", true, Usage.UNKNOWN));
                                subscriber.onComplete();
                            }
                        }
                    }
                    @Override public void cancel() { stage = 2; }
                });
            }
        };
        try (RoutedChatModel route = RoutedChatModel.builder().target("stream", streaming).build();
             RoutedChatModels localModels = new RoutedChatModels(Map.of("stream", route))) {
            var mockMvc = MockMvcBuilders.standaloneSetup(new OpenAiController(localModels)).build();
            MvcResult pending = mockMvc.perform(post("/v1/responses")
                            .contentType("application/json").accept("text/event-stream")
                            .content("{\"model\":\"stream\",\"stream\":true,\"input\":\"hello\"}"))
                    .andExpect(request().asyncStarted()).andReturn();

            pending.getAsyncResult(2_000);
            String body = mockMvc.perform(asyncDispatch(pending)).andReturn().getResponse().getContentAsString();
            assertThat(body).contains(
                    "event:response.created",
                    "event:response.output_item.added",
                    "event:response.content_part.added",
                    "event:response.output_text.delta",
                    "event:response.output_text.done",
                    "event:response.content_part.done",
                    "event:response.output_item.done",
                    "event:response.completed");
            assertThat(body.indexOf("response.output_item.added"))
                    .isLessThan(body.indexOf("response.output_text.delta"));
            assertThat(body.indexOf("response.output_text.delta"))
                    .isLessThan(body.indexOf("response.output_item.done"));
        }
    }

    @Test
    void streamsSanitizedResponsesFailureEventWithoutNetworkPort() throws Exception {
        ChatModel failing = new ChatModel() {
            @Override public ChatResponse chat(ChatRequest request) { throw new IllegalStateException("secret upstream detail"); }
            @Override public Flow.Publisher<ChatChunk> stream(ChatRequest request) {
                return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                    private boolean terminated;
                    @Override public void request(long n) {
                        if (!terminated && n > 0) {
                            terminated = true;
                            subscriber.onError(new IllegalStateException("secret upstream detail"));
                        }
                    }
                    @Override public void cancel() { terminated = true; }
                });
            }
        };
        try (RoutedChatModel route = RoutedChatModel.builder().target("failing", failing).build();
             RoutedChatModels localModels = new RoutedChatModels(Map.of("failing", route))) {
            var mockMvc = MockMvcBuilders.standaloneSetup(new OpenAiController(localModels)).build();
            MvcResult pending = mockMvc.perform(post("/v1/responses")
                            .contentType("application/json").accept("text/event-stream")
                            .content("{\"model\":\"failing\",\"stream\":true,\"input\":\"hello\"}"))
                    .andExpect(request().asyncStarted()).andReturn();

            pending.getAsyncResult(2_000);
            String body = mockMvc.perform(asyncDispatch(pending)).andReturn().getResponse().getContentAsString();
            assertThat(body).contains("event:response.created", "event:response.failed",
                    "server_error", "model execution failed");
            assertThat(body).doesNotContain("secret upstream detail");
        }
    }
}
