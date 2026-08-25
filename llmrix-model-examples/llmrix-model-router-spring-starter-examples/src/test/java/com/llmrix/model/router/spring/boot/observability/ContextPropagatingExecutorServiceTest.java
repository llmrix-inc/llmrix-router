package com.llmrix.model.router.spring.boot.observability;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshotFactory;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ContextPropagatingExecutorServiceTest {
    @Test
    void capturesAndRestoresRegisteredThreadLocalContext() throws Exception {
        ThreadLocal<String> correlationId = new ThreadLocal<>();
        ContextRegistry registry = new ContextRegistry()
                .registerThreadLocalAccessor("test.correlation-id", correlationId);
        ContextSnapshotFactory snapshots = ContextSnapshotFactory.builder()
                .contextRegistry(registry).build();
        var delegate = Executors.newSingleThreadExecutor();
        var executor = new ContextPropagatingExecutorService(delegate, snapshots);
        try {
            delegate.submit(() -> correlationId.set("worker-baseline")).get(2, TimeUnit.SECONDS);
            correlationId.set("request-123");

            assertThat(executor.submit(correlationId::get).get(2, TimeUnit.SECONDS))
                    .isEqualTo("request-123");
            assertThat(delegate.submit(correlationId::get).get(2, TimeUnit.SECONDS))
                    .isEqualTo("worker-baseline");
        } finally {
            correlationId.remove();
            executor.shutdownNow();
        }
    }
}
