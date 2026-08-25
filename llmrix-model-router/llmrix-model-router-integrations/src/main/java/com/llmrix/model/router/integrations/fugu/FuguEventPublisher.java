package com.llmrix.model.router.integrations.fugu;

import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.ForkJoinPool;

/**
 * Backpressure-aware bridge from Fugu lifecycle callbacks to a typed event stream.
 */
public final class FuguEventPublisher implements FuguListener, Flow.Publisher<Object>, AutoCloseable {
    private final SubmissionPublisher<Object> publisher;

    public FuguEventPublisher() {
        this(Flow.defaultBufferSize());
    }

    public FuguEventPublisher(int maxBufferCapacity) {
        if (maxBufferCapacity < 1) throw new IllegalArgumentException("maxBufferCapacity must be > 0");
        this.publisher = new SubmissionPublisher<>(ForkJoinPool.commonPool(), maxBufferCapacity);
    }

    @Override
    public void subscribe(Flow.Subscriber<? super Object> subscriber) {
        publisher.subscribe(subscriber);
    }

    @Override
    public void onStarted(FuguStarted event) {
        publisher.submit(event);
    }

    @Override
    public void onTurnStarted(FuguTurnStarted event) {
        publisher.submit(event);
    }

    @Override
    public void onTurnCompleted(FuguTurnCompleted event) {
        publisher.submit(event);
    }

    @Override
    public void onFallback(FuguFallback event) {
        publisher.submit(event);
    }

    @Override
    public void onCandidateCooldown(FuguCandidateCooldown event) {
        publisher.submit(event);
    }

    @Override
    public void onRetry(FuguRetry event) {
        publisher.submit(event);
    }

    @Override
    public void onCompleted(FuguCompleted event) {
        publisher.submit(event);
    }

    @Override
    public void close() {
        publisher.close();
    }
}
