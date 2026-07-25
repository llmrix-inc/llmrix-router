package com.llmrix.model.router.integrations.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmrix.model.router.core.api.ChatModel;
import com.llmrix.model.router.core.api.ChatRequest;
import com.llmrix.model.router.core.api.ChatResponse;
import com.llmrix.model.router.core.api.ResponseFormat;
import com.llmrix.model.router.core.api.ChatChunk;
import com.llmrix.model.router.core.api.ToolCallAccumulator;
import com.llmrix.model.router.core.exception.InvalidRequestException;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.ArrayList;
import java.util.Map;

/** Adds response validation without coupling Core to a JSON Schema implementation. */
public final class ValidatingChatModel implements ChatModel {
    private final ChatModel delegate;
    private final ResponseValidator validator;

    public ValidatingChatModel(ChatModel delegate, ResponseValidator validator) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    public static ValidatingChatModel jsonSyntax(ChatModel delegate) {
        ObjectMapper mapper = new ObjectMapper();
        return new ValidatingChatModel(delegate, jsonSyntaxValidator(mapper));
    }

    public static ValidatingChatModel standard(ChatModel delegate) {
        ObjectMapper mapper = new ObjectMapper();
        return new ValidatingChatModel(delegate, new CompositeResponseValidator(
                jsonSyntaxValidator(mapper),
                new NetworkntResponseValidator(mapper),
                new NetworkntToolArgumentsValidator(mapper)));
    }

    private static ResponseValidator jsonSyntaxValidator(ObjectMapper mapper) {
        return (request, response) -> {
            ResponseFormat format = request.responseFormat();
            if (format == null || format.type() == ResponseFormat.Type.TEXT) return;
            try {
                JsonNode parsed = mapper.readTree(response.text());
                if (parsed == null || (format.type() == ResponseFormat.Type.JSON_OBJECT && !parsed.isObject())
                        || (!parsed.isObject() && !parsed.isArray())) {
                    throw new InvalidRequestException(format.type() == ResponseFormat.Type.JSON_OBJECT
                            ? "model response is not a JSON object" : "model response is not a JSON object or array");
                }
            } catch (InvalidRequestException e) {
                throw e;
            } catch (Exception e) {
                throw new InvalidRequestException("model response is not valid JSON");
            }
        };
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        ChatResponse response = delegate.chat(request);
        validator.validate(request, response);
        return response;
    }

    @Override
    public CompletionStage<ChatResponse> chatAsync(ChatRequest request) {
        CompletableFuture<ChatResponse> upstream = delegate.chatAsync(request).toCompletableFuture();
        CompletableFuture<ChatResponse> result = new CompletableFuture<>();
        upstream.whenComplete((response, failure) -> {
            if (failure != null) {
                result.completeExceptionally(failure);
                return;
            }
            try {
                validator.validate(request, response);
                result.complete(response);
            } catch (RuntimeException error) {
                result.completeExceptionally(error);
            }
        });
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled()) upstream.cancel(true);
        });
        return result;
    }

    @Override
    public Flow.Publisher<ChatChunk> stream(ChatRequest request) {
        return downstream -> delegate.stream(request).subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription upstream;
            private final StringBuilder text = new StringBuilder();
            private final ToolCallAccumulator toolCalls = new ToolCallAccumulator();
            private boolean terminated;

            @Override public void onSubscribe(Flow.Subscription subscription) {
                upstream = subscription;
                downstream.onSubscribe(new Flow.Subscription() {
                    @Override public void request(long n) { upstream.request(n); }
                    @Override public void cancel() { terminated = true; upstream.cancel(); }
                });
            }

            @Override public void onNext(ChatChunk chunk) {
                if (terminated) return;
                text.append(chunk.text());
                toolCalls.add(chunk);
                if (chunk.finished()) {
                    terminated = true;
                    try {
                        ChatResponse response = new ChatResponse(text.toString(), null, chunk.usage(),
                                Map.of(), toolCalls.isEmpty() ? java.util.List.of() : toolCalls.finish(),
                                chunk.finishReason());
                        validator.validate(request, response);
                        downstream.onNext(chunk);
                        downstream.onComplete();
                    } catch (RuntimeException error) {
                        downstream.onError(error);
                        upstream.cancel();
                    }
                } else {
                    downstream.onNext(chunk);
                }
            }

            @Override public void onError(Throwable throwable) {
                if (!terminated) { terminated = true; downstream.onError(throwable); }
            }

            @Override public void onComplete() {
                if (!terminated) { terminated = true; downstream.onComplete(); }
            }
        });
    }
}
