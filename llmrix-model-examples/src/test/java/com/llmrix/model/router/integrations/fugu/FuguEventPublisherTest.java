package com.llmrix.model.router.integrations.fugu;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class FuguEventPublisherTest {
    @Test
    void publishesLifecycleEventsToSubscriber() throws Exception {
        FuguEventPublisher publisher = new FuguEventPublisher(16);
        List<Object> events = new CopyOnWriteArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);
        publisher.subscribe(new Flow.Subscriber<>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(Object event) { events.add(event); if (event instanceof FuguCompleted) completed.countDown(); }
            public void onError(Throwable error) { }
            public void onComplete() { }
        });
        publisher.onStarted(new FuguStarted("req", 2));
        publisher.onTurnStarted(new FuguTurnStarted("req", 0, "model", FuguRole.WORKER));
        publisher.onCompleted(new FuguCompleted("req", 1, 10, true, "done", null));
        assertThat(completed.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(events).hasSize(3);
        publisher.close();
    }
}
