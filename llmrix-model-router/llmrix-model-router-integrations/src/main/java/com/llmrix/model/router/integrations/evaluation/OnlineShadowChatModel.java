package com.llmrix.model.router.integrations.evaluation;

import com.llmrix.model.router.core.api.chat.ChatChunk;
import com.llmrix.model.router.core.api.chat.ChatModel;
import com.llmrix.model.router.core.api.chat.ChatRequest;
import com.llmrix.model.router.core.api.chat.ChatResponse;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Executes sampled, side-effect-free shadow calls without affecting the primary result.
 */
public final class OnlineShadowChatModel implements ChatModel {
    private final ChatModel primary;
    private final Map<String, ChatModel> shadows;
    private final double sampleRate;
    private final Duration timeout;
    private final ExecutorService executor;
    private final Semaphore permits;
    private final OnlineShadowListener listener;

    public OnlineShadowChatModel(ChatModel primary, Map<String, ChatModel> shadows, double sampleRate,
                                 int maxConcurrency, Duration timeout, ExecutorService executor,
                                 OnlineShadowListener listener) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.shadows = Map.copyOf(shadows);
        if (!Double.isFinite(sampleRate) || sampleRate < 0 || sampleRate > 1)
            throw new IllegalArgumentException("sampleRate must be between 0 and 1");
        if (maxConcurrency < 1) throw new IllegalArgumentException("maxConcurrency must be > 0");
        if (timeout == null || timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("timeout must be positive");
        this.sampleRate = sampleRate;
        this.timeout = timeout;
        this.executor = Objects.requireNonNull(executor, "executor");
        this.permits = new Semaphore(maxConcurrency);
        this.listener = listener == null ? OnlineShadowListener.NOOP : listener;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        launch(request);
        return primary.chat(request);
    }

    @Override
    public java.util.concurrent.CompletionStage<ChatResponse> chatAsync(ChatRequest request) {
        launch(request);
        return primary.chatAsync(request);
    }

    @Override
    public Flow.Publisher<ChatChunk> stream(ChatRequest request) {
        return subscriber -> {
            launch(request);
            primary.stream(request).subscribe(subscriber);
        };
    }

    private void launch(ChatRequest request) {
        if (!request.tools().isEmpty() || shadows.isEmpty() || ThreadLocalRandom.current().nextDouble() >= sampleRate)
            return;
        shadows.forEach((id, shadow) -> {
            if (!permits.tryAcquire()) return;
            executor.execute(() -> {
                long started = System.nanoTime();
                Throwable failure = null;
                try {
                    shadow.chatAsync(request).toCompletableFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                } catch (Throwable error) {
                    failure = unwrap(error);
                } finally {
                    permits.release();
                    Throwable result = failure;
                    try {
                        listener.completed(id, System.nanoTime() - started, result == null,
                                result == null ? null : result.getClass().getSimpleName());
                    } catch (RuntimeException ignored) {
                    }
                }
            });
        });
    }

    private static Throwable unwrap(Throwable error) {
        if ((error instanceof java.util.concurrent.ExecutionException
                || error instanceof java.util.concurrent.CompletionException) && error.getCause() != null)
            return error.getCause();
        return error;
    }
}
