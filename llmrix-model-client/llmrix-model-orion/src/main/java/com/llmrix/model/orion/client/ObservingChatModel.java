package com.llmrix.model.orion.client;

import com.llmrix.model.orion.observation.OrionModelClientListener;

import com.llmrix.model.router.core.api.ChatChunk;
import com.llmrix.model.router.core.api.ChatModel;
import com.llmrix.model.router.core.api.ChatRequest;
import com.llmrix.model.router.core.api.ChatResponse;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

final class ObservingChatModel implements ChatModel {
    private final ChatModel delegate;
    private final OrionModelClientListener listener;
    private final String requestId;
    private final String operation;
    private final String model;

    ObservingChatModel(ChatModel delegate, OrionModelClientListener listener,
                       String requestId, String operation, String model) {
        this.delegate = Objects.requireNonNull(delegate);
        this.listener = Objects.requireNonNull(listener);
        this.requestId = requestId;
        this.operation = operation;
        this.model = model;
    }

    @Override public ChatResponse chat(ChatRequest request) {
        Invocation invocation = start();
        try {
            ChatResponse response = delegate.chat(request);
            invocation.complete(null);
            return response;
        } catch (RuntimeException error) {
            invocation.complete(error);
            throw error;
        }
    }

    @Override public CompletionStage<ChatResponse> chatAsync(ChatRequest request) {
        Invocation invocation = start();
        CompletionStage<ChatResponse> upstream;
        try {
            upstream = delegate.chatAsync(request);
        } catch (RuntimeException error) {
            invocation.complete(error);
            throw error;
        }
        CompletableFuture<ChatResponse> result = new CompletableFuture<>();
        upstream.whenComplete((response, error) -> {
            Throwable failure = unwrap(error);
            invocation.complete(failure);
            if (failure == null) result.complete(response); else result.completeExceptionally(failure);
        });
        result.whenComplete((ignored, ignoredError) -> {
            if (result.isCancelled()) {
                upstream.toCompletableFuture().cancel(true);
                invocation.complete(new java.util.concurrent.CancellationException("cancelled"));
            }
        });
        return result;
    }

    @Override public Flow.Publisher<ChatChunk> stream(ChatRequest request) {
        return subscriber -> {
            Invocation invocation = start();
            try {
                delegate.stream(request).subscribe(new Flow.Subscriber<>() {
                    private Flow.Subscription upstream;
                    private final AtomicBoolean first = new AtomicBoolean();
                    @Override public void onSubscribe(Flow.Subscription subscription) {
                        upstream = subscription;
                        subscriber.onSubscribe(new Flow.Subscription() {
                            @Override public void request(long n) { subscription.request(n); }
                            @Override public void cancel() {
                                subscription.cancel();
                                invocation.complete(new java.util.concurrent.CancellationException("cancelled"));
                            }
                        });
                    }
                    @Override public void onNext(ChatChunk item) {
                        if (first.compareAndSet(false, true)) invocation.firstToken();
                        subscriber.onNext(item);
                    }
                    @Override public void onError(Throwable error) {
                        invocation.complete(error);
                        subscriber.onError(error);
                    }
                    @Override public void onComplete() {
                        invocation.complete(null);
                        subscriber.onComplete();
                    }
                });
            } catch (RuntimeException error) {
                invocation.complete(error);
                throw error;
            }
        };
    }

    private Invocation start() {
        String invocationId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        notifySafely(() -> listener.onStarted(new OrionModelClientListener.RequestStarted(
                invocationId, requestId, operation, model, started)));
        return new Invocation(invocationId, started);
    }

    private final class Invocation {
        private final String id;
        private final long started;
        private final AtomicBoolean completed = new AtomicBoolean();
        private Invocation(String id, long started) { this.id = id; this.started = started; }
        private void firstToken() {
            notifySafely(() -> listener.onFirstToken(new OrionModelClientListener.FirstToken(
                    id, System.nanoTime() - started)));
        }
        private void complete(Throwable error) {
            if (!completed.compareAndSet(false, true)) return;
            notifySafely(() -> listener.onCompleted(new OrionModelClientListener.RequestCompleted(
                    id, System.nanoTime() - started, error == null,
                    error == null ? null : error.getClass().getSimpleName())));
        }
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof java.util.concurrent.CompletionException completion && completion.getCause() != null) {
            return completion.getCause();
        }
        return error;
    }

    private static void notifySafely(Runnable notification) {
        try { notification.run(); } catch (RuntimeException ignored) { }
    }
}
