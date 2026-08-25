package com.llmrix.model.router.spring.boot.observability;

import io.micrometer.context.ContextSnapshotFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Captures registered Micrometer thread-local context when a task is submitted.
 */
public final class ContextPropagatingExecutorService extends AbstractExecutorService {
    private final ExecutorService delegate;
    private final ContextSnapshotFactory snapshots;

    public ContextPropagatingExecutorService(ExecutorService delegate) {
        this(delegate, ContextSnapshotFactory.builder().build());
    }

    public ContextPropagatingExecutorService(ExecutorService delegate, ContextSnapshotFactory snapshots) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(snapshots.captureAll().wrap(command));
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }
}
