package com.llmrix.model.router.spring.boot.observability;

import com.llmrix.model.router.core.api.Usage;
import com.llmrix.model.router.integrations.fugu.FuguCompleted;
import com.llmrix.model.router.integrations.fugu.FuguRole;
import com.llmrix.model.router.integrations.fugu.FuguStarted;
import com.llmrix.model.router.integrations.fugu.FuguTurnCompleted;
import com.llmrix.model.router.integrations.fugu.FuguTurnStarted;
import com.llmrix.model.router.integrations.fugu.FuguFallback;
import com.llmrix.model.router.integrations.fugu.FuguCandidateCooldown;
import com.llmrix.model.router.integrations.fugu.FuguRetry;
import java.time.Duration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerFuguObservationListenerTest {
    @Test
    void publishesOrchestrationAndTurnMetrics() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        MicrometerFuguObservationListener listener = new MicrometerFuguObservationListener(
                ObservationRegistry.NOOP, meters, true);

        listener.onStarted(new FuguStarted("request", 5));
        listener.onTurnStarted(new FuguTurnStarted("request", 0, "worker", FuguRole.WORKER));
        listener.onTurnCompleted(new FuguTurnCompleted(
                "request", 0, "worker", FuguRole.WORKER, 1_000_000, new Usage(2, 3)));
        listener.onFallback(new FuguFallback("request", "worker", "backup", "RateLimitException"));
        listener.onCandidateCooldown(new FuguCandidateCooldown("request", "worker", Duration.ofSeconds(30)));
        listener.onRetry(new FuguRetry("request", "worker", 2, "RateLimitException"));
        listener.onCompleted(new FuguCompleted("request", 1, 2_000_000,
                true, "quality-threshold", null));

        assertThat(meters.find("llm.fugu.requests").counter().count()).isEqualTo(1);
        assertThat(meters.find("llm.fugu.turn.duration").tag("candidate", "worker").timer().count()).isEqualTo(1);
        assertThat(meters.find("llm.fugu.orchestration.duration")
                .tag("termination", "quality-threshold").timer().count()).isEqualTo(1);
        assertThat(meters.find("llm.fugu.fallbacks").counter().count()).isEqualTo(1);
        assertThat(meters.find("llm.fugu.cooldowns").tag("candidate", "worker").counter().count()).isEqualTo(1);
        assertThat(meters.find("llm.fugu.retries").tag("candidate", "worker").counter().count()).isEqualTo(1);
    }
}
