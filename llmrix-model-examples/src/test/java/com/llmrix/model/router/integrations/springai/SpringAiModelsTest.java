package com.llmrix.model.router.integrations.springai;

import com.llmrix.model.router.core.api.ChatModel;
import com.llmrix.model.router.core.api.ChatResponse;
import com.llmrix.model.router.core.api.ChatChunk;
import com.llmrix.model.router.core.api.ChatRequest;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Flux;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpringAiModelsTest {

    @Test
    void adaptsSpringAiModelToCore() {
        org.springframework.ai.chat.model.ChatModel springModel = prompt ->
                new org.springframework.ai.chat.model.ChatResponse(
                        List.of(new Generation(new AssistantMessage("spring"))));

        ChatModel core = SpringAiModels.adapt(springModel);

        assertEquals("spring", core.chat("hello").text());
    }

    @Test
    void exposesCoreModelAsSpringAiModel() {
        ChatModel core = request -> ChatResponse.of("core");

        org.springframework.ai.chat.model.ChatModel springModel = SpringAiModels.expose(core);

        assertEquals("core", springModel.call("hello"));
    }

    @Test
    void detectsAndAdaptsNativeSpringAiStreaming() throws Exception {
        class DualModel implements org.springframework.ai.chat.model.ChatModel,
                org.springframework.ai.chat.model.StreamingChatModel {
            @Override public org.springframework.ai.chat.model.ChatResponse call(
                    org.springframework.ai.chat.prompt.Prompt prompt) {
                return response("sync");
            }
            @Override public Flux<org.springframework.ai.chat.model.ChatResponse> stream(
                    org.springframework.ai.chat.prompt.Prompt prompt) {
                return Flux.just(response("hel"), response("lo"));
            }
            private org.springframework.ai.chat.model.ChatResponse response(String text) {
                return new org.springframework.ai.chat.model.ChatResponse(
                        List.of(new Generation(new AssistantMessage(text))));
            }
        }
        ChatModel core = SpringAiModels.adaptObject(new DualModel());
        List<ChatChunk> chunks = new ArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);

        core.stream(ChatRequest.user("hello")).subscribe(new Flow.Subscriber<>() {
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
        AtomicReference<org.springframework.ai.chat.prompt.Prompt> captured = new AtomicReference<>();
        org.springframework.ai.chat.model.ChatModel springModel = prompt -> {
            captured.set(prompt);
            AssistantMessage output = AssistantMessage.builder().content("").toolCalls(List.of(
                    new AssistantMessage.ToolCall("call_2", "function", "weather",
                            "{\"city\":\"Shanghai\"}"))).build();
            return new org.springframework.ai.chat.model.ChatResponse(List.of(new Generation(output)));
        };
        ChatModel core = SpringAiModels.adapt(springModel);
        ChatResponse response = core.chat(ChatRequest.builder()
                .message(com.llmrix.model.router.core.api.Message.assistant(new com.llmrix.model.router.core.api.ToolCallPart(
                        "call_1", "weather", "{\"city\":\"Beijing\"}")))
                .message(com.llmrix.model.router.core.api.Message.tool("call_1", "{\"temperature\":25}"))
                .tools(new com.llmrix.model.router.core.api.ToolDefinition("weather", "Get weather", Map.of(
                        "type", "object", "properties", Map.of("city", Map.of("type", "string"))), false))
                .toolChoice(com.llmrix.model.router.core.api.ToolChoice.auto()).build());

        var options = (org.springframework.ai.model.tool.ToolCallingChatOptions) captured.get().getOptions();
        assertEquals("weather", options.getToolCallbacks().get(0).getToolDefinition().name());
        assertEquals(false, options.getInternalToolExecutionEnabled());
        assertEquals(org.springframework.ai.chat.messages.MessageType.TOOL,
                captured.get().getInstructions().get(1).getMessageType());
        assertEquals("weather", ((org.springframework.ai.chat.messages.ToolResponseMessage)
                captured.get().getInstructions().get(1)).getResponses().get(0).name());
        assertEquals("call_2", response.toolCalls().get(0).id());
        assertEquals("tool_calls", response.finishReason());
    }

    @Test
    void mapsSpringAiToolsIntoExposedCoreModel() {
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        ChatModel core = request -> { captured.set(request); return ChatResponse.of("ok"); };
        org.springframework.ai.chat.model.ChatModel exposed = SpringAiModels.expose(core);
        org.springframework.ai.tool.definition.ToolDefinition definition =
                org.springframework.ai.tool.definition.ToolDefinition.builder()
                        .name("weather").description("Get weather")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}")
                        .build();
        org.springframework.ai.tool.ToolCallback callback = new org.springframework.ai.tool.ToolCallback() {
            @Override public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() { return definition; }
            @Override public String call(String input) { return "unused"; }
        };
        var options = org.springframework.ai.model.tool.ToolCallingChatOptions.builder()
                .toolCallbacks(callback).internalToolExecutionEnabled(false)
                .temperature(0.3).topP(0.8).maxTokens(12).stopSequences(List.of("END")).build();

        exposed.call(new org.springframework.ai.chat.prompt.Prompt(
                List.of(new org.springframework.ai.chat.messages.UserMessage("weather")), options));

        assertEquals("weather", captured.get().tools().get(0).name());
        assertEquals("string", ((Map<?, ?>) ((Map<?, ?>) captured.get().tools().get(0).parameters()
                .get("properties")).get("city")).get("type"));
        assertEquals(0.3, captured.get().generationOptions().temperature());
        assertEquals(0.8, captured.get().generationOptions().topP());
        assertEquals(12, captured.get().generationOptions().maxOutputTokens());
        assertEquals(List.of("END"), captured.get().generationOptions().stop());
    }

    @Test
    void rejectsGenerationOptionsMissingFromGenericSpringAiContract() {
        org.springframework.ai.chat.model.ChatModel springModel = prompt ->
                new org.springframework.ai.chat.model.ChatResponse(
                        List.of(new Generation(new AssistantMessage("unused"))));
        ChatModel core = SpringAiModels.adapt(springModel);

        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> core.chat(ChatRequest.builder().userMessage("hello")
                        .generationOptions(com.llmrix.model.router.core.api.GenerationOptions.builder()
                                .logprobs(true).build()).build()));
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> core.chat(ChatRequest.builder().userMessage("hello")
                        .responseFormat(com.llmrix.model.router.core.api.ResponseFormat.jsonObject()).build()));
    }

    @Test
    void mapsSpringAiStreamingToolCallsToCoreDeltas() throws Exception {
        org.springframework.ai.chat.model.ChatModel sync = prompt ->
                new org.springframework.ai.chat.model.ChatResponse(List.of(
                        new Generation(new AssistantMessage("unused"))));
        org.springframework.ai.chat.model.StreamingChatModel streaming = prompt -> Flux.just(
                new org.springframework.ai.chat.model.ChatResponse(List.of(new Generation(
                        AssistantMessage.builder().content("").toolCalls(List.of(
                                new AssistantMessage.ToolCall("call_1", "function", "weather",
                                        "{\"city\":\"Shanghai\"}"))).build()))));
        ChatModel core = SpringAiModels.adapt(sync, streaming);
        List<ChatChunk> chunks = new ArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);

        core.stream(ChatRequest.user("weather")).subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ChatChunk item) { chunks.add(item); }
            @Override public void onError(Throwable throwable) { completed.countDown(); }
            @Override public void onComplete() { completed.countDown(); }
        });

        org.junit.jupiter.api.Assertions.assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertEquals("call_1", chunks.get(0).toolCallDeltas().get(0).id());
        assertEquals("weather", chunks.get(0).toolCallDeltas().get(0).name());
        assertEquals("tool_calls", chunks.get(1).finishReason());
    }
}
