package com.llmrix.model.router.integrations.langchain4j;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import com.llmrix.model.router.core.api.ChatModel;
import com.llmrix.model.router.core.api.ChatChunk;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

class LangChain4jModelsTest {

    @Test
    void adaptsLangChain4jModelToCore() {
        dev.langchain4j.model.chat.ChatModel langChain4j = new dev.langchain4j.model.chat.ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from("langchain4j")).build();
            }
        };

        ChatModel core = LangChain4jModels.adapt(langChain4j);

        assertEquals("langchain4j", core.chat("hello").text());
    }

    @Test
    void exposesCoreModelAsLangChain4jModel() {
        ChatModel core = request -> com.llmrix.model.router.core.api.ChatResponse.of("core");

        dev.langchain4j.model.chat.ChatModel langChain4j = LangChain4jModels.expose(core);

        assertEquals("core", langChain4j.chat("hello"));
    }

    @Test
    void detectsAndAdaptsNativeLangChain4jStreaming() throws Exception {
        class DualModel implements dev.langchain4j.model.chat.ChatModel,
                dev.langchain4j.model.chat.StreamingChatModel {
            @Override public java.util.Set<dev.langchain4j.model.chat.Capability> supportedCapabilities() {
                return java.util.Set.of();
            }
            @Override public dev.langchain4j.model.ModelProvider provider() {
                return dev.langchain4j.model.ModelProvider.OTHER;
            }
            @Override public java.util.List<dev.langchain4j.model.chat.listener.ChatModelListener> listeners() {
                return java.util.List.of();
            }
            @Override public dev.langchain4j.model.chat.request.ChatRequestParameters defaultRequestParameters() {
                return dev.langchain4j.model.chat.request.ChatRequestParameters.builder().build();
            }
            @Override public ChatResponse doChat(ChatRequest request) { return response("sync"); }
            @Override public void doChat(ChatRequest request,
                    dev.langchain4j.model.chat.response.StreamingChatResponseHandler handler) {
                handler.onPartialResponse("hel");
                handler.onPartialResponse("lo");
                handler.onCompleteResponse(response("hello"));
            }
            private ChatResponse response(String text) {
                return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
            }
        }
        ChatModel core = LangChain4jModels.adaptObject(new DualModel());
        List<ChatChunk> chunks = new ArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);

        core.stream(com.llmrix.model.router.core.api.ChatRequest.user("hello")).subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }
            @Override public void onNext(ChatChunk item) { chunks.add(item); }
            @Override public void onError(Throwable throwable) { completed.countDown(); }
            @Override public void onComplete() { completed.countDown(); }
        });

        assertEquals("sync", core.chat("hello").text());
        org.junit.jupiter.api.Assertions.assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertEquals(List.of("hel", "lo", ""), chunks.stream().map(ChatChunk::text).toList());
        org.junit.jupiter.api.Assertions.assertTrue(chunks.get(2).finished());
    }

    @Test
    void mapsCoreToolsMessagesAndToolCallResponse() {
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        dev.langchain4j.model.chat.ChatModel nativeModel = new dev.langchain4j.model.chat.ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                captured.set(request);
                var call = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                        .id("call_2").name("weather").arguments("{\"city\":\"Shanghai\"}").build();
                return ChatResponse.builder().aiMessage(AiMessage.from(List.of(call))).build();
            }
        };
        ChatModel core = LangChain4jModels.adapt(nativeModel);
        com.llmrix.model.router.core.api.ChatResponse response = core.chat(com.llmrix.model.router.core.api.ChatRequest.builder()
                .message(com.llmrix.model.router.core.api.Message.assistant(new com.llmrix.model.router.core.api.ToolCallPart(
                        "call_1", "weather", "{\"city\":\"Beijing\"}")))
                .message(com.llmrix.model.router.core.api.Message.tool("call_1", "{\"temperature\":25}"))
                .tools(new com.llmrix.model.router.core.api.ToolDefinition("weather", "Get weather", Map.of(
                        "type", "object", "properties", Map.of("city", Map.of("type", "string")),
                        "required", List.of("city")), false))
                .toolChoice(com.llmrix.model.router.core.api.ToolChoice.required()).build());

        assertEquals(dev.langchain4j.model.chat.request.ToolChoice.REQUIRED, captured.get().toolChoice());
        assertEquals("weather", captured.get().toolSpecifications().get(0).name());
        assertEquals("weather", ((dev.langchain4j.data.message.ToolExecutionResultMessage)
                captured.get().messages().get(1)).toolName());
        assertEquals("call_2", response.toolCalls().get(0).id());
        assertEquals("tool_calls", response.finishReason());
    }

    @Test
    void mapsLangChain4jToolsIntoExposedCoreModel() {
        AtomicReference<com.llmrix.model.router.core.api.ChatRequest> captured = new AtomicReference<>();
        ChatModel core = request -> { captured.set(request); return com.llmrix.model.router.core.api.ChatResponse.of("ok"); };
        dev.langchain4j.model.chat.ChatModel exposed = LangChain4jModels.expose(core);
        var schema = dev.langchain4j.model.chat.request.json.JsonObjectSchema.builder()
                .addStringProperty("city").required("city").build();
        var tool = dev.langchain4j.agent.tool.ToolSpecification.builder()
                .name("weather").description("Get weather").parameters(schema).build();

        exposed.chat(ChatRequest.builder().messages(dev.langchain4j.data.message.UserMessage.from("weather"))
                .toolSpecifications(tool)
                .toolChoice(dev.langchain4j.model.chat.request.ToolChoice.AUTO)
                .temperature(0.3).topP(0.8).maxOutputTokens(12).stopSequences(List.of("END"))
                .responseFormat(dev.langchain4j.model.chat.request.ResponseFormat.JSON).build());

        assertEquals("weather", captured.get().tools().get(0).name());
        assertEquals("string", ((Map<?, ?>) ((Map<?, ?>) captured.get().tools().get(0).parameters()
                .get("properties")).get("city")).get("type"));
        assertEquals(com.llmrix.model.router.core.api.ToolChoice.auto(), captured.get().toolChoice());
        assertEquals(0.3, captured.get().generationOptions().temperature());
        assertEquals(0.8, captured.get().generationOptions().topP());
        assertEquals(12, captured.get().generationOptions().maxOutputTokens());
        assertEquals(List.of("END"), captured.get().generationOptions().stop());
        assertEquals(com.llmrix.model.router.core.api.ResponseFormat.Type.JSON_OBJECT,
                captured.get().responseFormat().type());
    }

    @Test
    void mapsCoreGenerationOptionsAndRejectsUnsupportedOnes() {
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        dev.langchain4j.model.chat.ChatModel nativeModel = new dev.langchain4j.model.chat.ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                captured.set(request);
                return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
            }
        };
        ChatModel core = LangChain4jModels.adapt(nativeModel);
        core.chat(com.llmrix.model.router.core.api.ChatRequest.builder().userMessage("hello")
                .generationOptions(com.llmrix.model.router.core.api.GenerationOptions.builder()
                        .temperature(0.4).topP(0.7).maxOutputTokens(20).stop("STOP").build()).build());

        assertEquals(0.4, captured.get().temperature());
        assertEquals(0.7, captured.get().topP());
        assertEquals(20, captured.get().maxOutputTokens());
        assertEquals(List.of("STOP"), captured.get().stopSequences());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> core.chat(com.llmrix.model.router.core.api.ChatRequest.builder().userMessage("hello")
                        .generationOptions(com.llmrix.model.router.core.api.GenerationOptions.builder().seed(1L).build())
                        .build()));
        core.chat(com.llmrix.model.router.core.api.ChatRequest.builder().userMessage("hello")
                .responseFormat(com.llmrix.model.router.core.api.ResponseFormat.jsonObject()).build());
        assertEquals(dev.langchain4j.model.chat.request.ResponseFormat.JSON,
                captured.get().responseFormat());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> core.chat(com.llmrix.model.router.core.api.ChatRequest.builder().userMessage("hello")
                        .responseFormat(com.llmrix.model.router.core.api.ResponseFormat.jsonSchema(
                                "answer", Map.of("type", "object"), true)).build()));
    }

    @Test
    void mapsLangChain4jPartialToolCallsWithoutDuplicatingFinalRequest() throws Exception {
        var call = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                .id("call_1").name("weather").arguments("{\"city\":\"Shanghai\"}").build();
        dev.langchain4j.model.chat.ChatModel sync = new dev.langchain4j.model.chat.ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from(List.of(call))).build();
            }
        };
        dev.langchain4j.model.chat.StreamingChatModel streaming =
                new dev.langchain4j.model.chat.StreamingChatModel() {
                    @Override public void doChat(ChatRequest request,
                            dev.langchain4j.model.chat.response.StreamingChatResponseHandler handler) {
                        handler.onPartialToolCall(dev.langchain4j.model.chat.response.PartialToolCall.builder()
                                .index(0).id("call_1").name("weather")
                                .partialArguments("{\"city\":").build());
                        handler.onPartialToolCall(dev.langchain4j.model.chat.response.PartialToolCall.builder()
                                .index(0).id("call_1").name("weather")
                                .partialArguments("\"Shanghai\"}").build());
                        handler.onCompleteResponse(ChatResponse.builder()
                                .aiMessage(AiMessage.from(List.of(call))).build());
                    }
                };
        ChatModel core = LangChain4jModels.adapt(sync, streaming);
        com.llmrix.model.router.core.api.ToolCallAccumulator accumulator =
                new com.llmrix.model.router.core.api.ToolCallAccumulator();
        AtomicReference<ChatChunk> finalChunk = new AtomicReference<>();
        AtomicReference<Throwable> streamError = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);

        core.stream(com.llmrix.model.router.core.api.ChatRequest.user("weather")).subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ChatChunk item) {
                accumulator.add(item);
                if (item.finished()) finalChunk.set(item);
            }
            @Override public void onError(Throwable throwable) {
                streamError.set(throwable);
                completed.countDown();
            }
            @Override public void onComplete() { completed.countDown(); }
        });

        org.junit.jupiter.api.Assertions.assertTrue(completed.await(2, TimeUnit.SECONDS));
        org.junit.jupiter.api.Assertions.assertNull(streamError.get());
        assertEquals(List.of(new com.llmrix.model.router.core.api.ToolCallPart(
                "call_1", "weather", "{\"city\":\"Shanghai\"}")), accumulator.finish());
        assertEquals("tool_calls", finalChunk.get().finishReason());
    }
}
