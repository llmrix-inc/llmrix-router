package com.llmrix.model.router.integrations.validation;

import com.llmrix.model.router.core.api.ChatRequest;
import com.llmrix.model.router.core.api.ChatResponse;
import com.llmrix.model.router.core.api.ResponseFormat;
import com.llmrix.model.router.core.exception.InvalidRequestException;
import com.llmrix.model.router.core.api.ToolCallPart;
import com.llmrix.model.router.core.api.ToolDefinition;
import com.llmrix.model.router.core.api.Usage;
import com.llmrix.model.router.core.api.ChatModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidatingChatModelTest {
    @Test
    void rejectsInvalidJsonForStructuredRequest() {
        var model = ValidatingChatModel.jsonSyntax(request -> ChatResponse.of("not-json"));
        ChatRequest request = ChatRequest.builder().userMessage("json").responseFormat(ResponseFormat.jsonObject()).build();
        assertThrows(InvalidRequestException.class, () -> model.chat(request));
    }

    @Test
    void acceptsTextForNormalRequest() {
        var model = ValidatingChatModel.jsonSyntax(request -> ChatResponse.of("plain"));
        model.chat(ChatRequest.user("hello"));
    }

    @Test
    void jsonObjectFormatRejectsArray() {
        var model = ValidatingChatModel.jsonSyntax(request -> ChatResponse.of("[1,2,3]"));
        ChatRequest request = ChatRequest.builder().userMessage("json")
                .responseFormat(ResponseFormat.jsonObject()).build();
        assertThrows(InvalidRequestException.class, () -> model.chat(request));
    }

    @Test
    void validatesJsonSchemaWithNetworknt() {
        var valid = new ValidatingChatModel(
                request -> ChatResponse.of("{\"answer\":\"ok\"}"), new NetworkntResponseValidator());
        var request = ChatRequest.builder().userMessage("json")
                .responseFormat(ResponseFormat.jsonSchema("answer", Map.of(
                        "type", "object",
                        "required", java.util.List.of("answer"),
                        "properties", Map.of("answer", Map.of("type", "string"))), true)).build();
        assertDoesNotThrow(() -> valid.chat(request));

        var invalid = new ValidatingChatModel(
                ignored -> ChatResponse.of("{\"answer\":42}"), new NetworkntResponseValidator());
        assertThrows(InvalidRequestException.class, () -> invalid.chat(request));
    }

    @Test
    void validatesToolArgumentsWithNetworknt() {
        ToolDefinition tool = new ToolDefinition("weather", null, Map.of(
                "type", "object", "required", List.of("city"),
                "properties", Map.of("city", Map.of("type", "string"))));
        ChatRequest request = ChatRequest.builder().userMessage("weather").tools(tool).build();
        var valid = new ValidatingChatModel(ignored -> new ChatResponse("", "model", Usage.UNKNOWN,
                Map.of(), List.of(new ToolCallPart("call", "weather", "{\"city\":\"Shanghai\"}"))),
                new NetworkntToolArgumentsValidator());
        assertDoesNotThrow(() -> valid.chat(request));

        var invalid = new ValidatingChatModel(ignored -> new ChatResponse("", "model", Usage.UNKNOWN,
                Map.of(), List.of(new ToolCallPart("call", "weather", "{\"city\":42}"))),
                new NetworkntToolArgumentsValidator());
        assertThrows(InvalidRequestException.class, () -> invalid.chat(request));
    }

    @Test
    void standardValidatorRunsCompleteValidationChain() {
        ChatRequest request = ChatRequest.builder().userMessage("json")
                .responseFormat(ResponseFormat.jsonSchema("answer", Map.of(
                        "type", "object", "required", List.of("answer"),
                        "properties", Map.of("answer", Map.of("type", "string"))), true))
                .build();
        var model = ValidatingChatModel.standard(ignored -> ChatResponse.of("{\"answer\":42}"));
        assertThrows(InvalidRequestException.class, () -> model.chat(request));
    }

    @Test
    void preservesNativeAsyncCancellation() {
        AtomicBoolean cancelled = new AtomicBoolean();
        CompletableFuture<ChatResponse> upstream = new CompletableFuture<>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                cancelled.set(true);
                return super.cancel(mayInterruptIfRunning);
            }
        };
        ChatModel delegate = new ChatModel() {
            @Override public ChatResponse chat(ChatRequest request) { throw new AssertionError("sync must not be used"); }
            @Override public CompletionStage<ChatResponse> chatAsync(ChatRequest request) { return upstream; }
        };
        CompletableFuture<ChatResponse> result = ValidatingChatModel.jsonSyntax(delegate)
                .chatAsync(ChatRequest.user("hello")).toCompletableFuture();

        result.cancel(true);

        assertTrue(cancelled.get());
    }

    @Test
    void validatesFinalStreamingChunkBeforeForwardingIt() throws Exception {
        ChatModel delegate = new ChatModel() {
            @Override public ChatResponse chat(ChatRequest request) { return ChatResponse.of("unused"); }
            @Override public java.util.concurrent.Flow.Publisher<com.llmrix.model.router.core.api.ChatChunk> stream(ChatRequest request) {
                return subscriber -> {
                    subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription() {
                        @Override public void request(long n) {
                            subscriber.onNext(new com.llmrix.model.router.core.api.ChatChunk("not-json", true, Usage.UNKNOWN));
                        }
                        @Override public void cancel() { }
                    });
                };
            }
        };
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean received = new AtomicBoolean();
        ValidatingChatModel.jsonSyntax(delegate).stream(ChatRequest.builder()
                        .userMessage("json").responseFormat(ResponseFormat.jsonObject()).build())
                .subscribe(new java.util.concurrent.Flow.Subscriber<>() {
                    @Override public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) { subscription.request(1); }
                    @Override public void onNext(com.llmrix.model.router.core.api.ChatChunk item) { received.set(true); }
                    @Override public void onError(Throwable throwable) { failure.set(throwable); }
                    @Override public void onComplete() { }
                });

        assertFalse(received.get());
        assertInstanceOf(InvalidRequestException.class, failure.get());
    }
}
