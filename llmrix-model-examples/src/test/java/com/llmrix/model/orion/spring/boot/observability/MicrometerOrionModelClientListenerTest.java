package com.llmrix.model.orion.spring.boot.observability;

import com.llmrix.model.orion.observation.OrionModelClientListener;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerOrionModelClientListenerTest {
    @Test
    void recordsRequestAndFirstTokenMetrics() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        MicrometerOrionModelClientListener listener = new MicrometerOrionModelClientListener(
                ObservationRegistry.create(), meters);

        listener.onStarted(new OrionModelClientListener.RequestStarted(
                "inv-1", "req-1", "responses", "general", 1));
        listener.onFirstToken(new OrionModelClientListener.FirstToken("inv-1", 10));
        listener.onCompleted(new OrionModelClientListener.RequestCompleted("inv-1", 20, true, null));

        assertThat(meters.get("llmrix.orion.requests").counter().count()).isEqualTo(1);
        assertThat(meters.get("llmrix.orion.first.token").timer().count()).isEqualTo(1);
        assertThat(meters.get("llmrix.orion.request.duration").tag("outcome", "success")
                .timer().count()).isEqualTo(1);
    }
}
