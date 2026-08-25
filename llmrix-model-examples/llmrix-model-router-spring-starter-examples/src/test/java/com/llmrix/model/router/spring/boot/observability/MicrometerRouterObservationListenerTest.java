package com.llmrix.model.router.spring.boot.observability;

import com.llmrix.model.router.core.event.AttemptCompleted;
import com.llmrix.model.router.core.event.RequestCompleted;
import com.llmrix.model.router.core.event.RequestStarted;
import com.llmrix.model.router.core.event.UsageRecorded;
import com.llmrix.model.router.core.event.TargetCooldown;
import com.llmrix.model.router.core.event.FirstTokenReceived;
import com.llmrix.model.router.core.event.RouteSelected;
import com.llmrix.model.router.core.event.AttemptStarted;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import com.llmrix.model.router.core.api.chat.ChatRequest;
import com.llmrix.model.router.core.api.Usage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerRouterObservationListenerTest {

    @Test
    void doesNotPublishMetersWhenMetricsAreDisabled() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        MicrometerRouterObservationListener listener = new MicrometerRouterObservationListener(
                ObservationRegistry.NOOP, meters, true, false, false);

        listener.onRequestStarted(new RequestStarted("request", System.nanoTime()));
        listener.onAttemptCompleted(new AttemptCompleted("request", "candidate", 1, 10, true, null));
        listener.onAttemptCompleted(new AttemptCompleted("request", "candidate", 2, 10, true, null));
        listener.onTargetCooldown(new TargetCooldown("request", "candidate", Duration.ofSeconds(60)));
        listener.onUsageRecorded(new UsageRecorded("request", "candidate", new Usage(10, 5), 0.25));
        listener.onFirstToken(new FirstTokenReceived("request", "candidate", 1_000_000));
        listener.onRequestCompleted(new RequestCompleted("request", "candidate", 20, true, 1));

        assertThat(meters.getMeters()).isEmpty();
    }

    @Test
    void publishesMetersWhenMetricsAreEnabled() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        MicrometerRouterObservationListener listener = new MicrometerRouterObservationListener(
                ObservationRegistry.NOOP, meters, true, true, false);

        listener.onRequestStarted(new RequestStarted("request", System.nanoTime()));
        listener.onAttemptCompleted(new AttemptCompleted("request", "candidate", 1, 10, true, null));
        listener.onAttemptCompleted(new AttemptCompleted("request", "candidate", 2, 10, true, null));
        listener.onTargetCooldown(new TargetCooldown("request", "candidate", Duration.ofSeconds(60)));
        listener.onUsageRecorded(new UsageRecorded("request", "candidate", new Usage(10, 5), 0.25));
        listener.onFirstToken(new FirstTokenReceived("request", "candidate", 1_000_000));
        listener.onRequestCompleted(new RequestCompleted("request", "candidate", 20, true, 1));

        assertThat(meters.find("llm.router.requests").counter()).isNotNull();
        assertThat(meters.find("llm.router.attempts").counter()).isNotNull();
        assertThat(meters.find("llm.router.retries").counter().count()).isEqualTo(1);
        assertThat(meters.find("llm.router.cooldowns").counter().count()).isEqualTo(1);
        assertThat(meters.find("llm.router.request.duration").timer()).isNotNull();
        assertThat(meters.find("llm.router.tokens").tag("type", "input").counter().count()).isEqualTo(10);
        assertThat(meters.find("llm.router.tokens").tag("type", "output").counter().count()).isEqualTo(5);
        assertThat(meters.find("llm.router.cost").counter().count()).isEqualTo(0.25);
        assertThat(meters.find("llm.router.first.token").tag("candidate", "candidate").timer().count()).isEqualTo(1);
    }

    @Test
    void suppressesCostMetricWhenCostIsDisabled() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        MicrometerRouterObservationListener listener = new MicrometerRouterObservationListener(
                ObservationRegistry.NOOP, meters, true, true, false, false);

        listener.onUsageRecorded(new UsageRecorded("request", "candidate", new Usage(10, 5), 0.25));

        assertThat(meters.find("llm.router.tokens").meters()).hasSize(2);
        assertThat(meters.find("llm.router.cost").counter()).isNull();
    }

    @Test
    void createsAndStopsRouterObservationHierarchy() {
        ObservationRegistry observations = ObservationRegistry.create();
        List<String> started = new ArrayList<>();
        List<String> stopped = new ArrayList<>();
        observations.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override public boolean supportsContext(Observation.Context context) { return true; }
            @Override public void onStart(Observation.Context context) { started.add(context.getName()); }
            @Override public void onStop(Observation.Context context) { stopped.add(context.getName()); }
        });
        MicrometerRouterObservationListener listener = new MicrometerRouterObservationListener(
                observations, new SimpleMeterRegistry(), true, false, true, true, true);

        listener.onRequestStarted(new RequestStarted("request", System.nanoTime()));
        listener.onRouteSelected(new RouteSelected("request", "primary", "balanced", "strategy:balanced"));
        listener.onAttemptStarted(new AttemptStarted("request", "primary", 1));
        listener.onAttemptCompleted(new AttemptCompleted("request", "primary", 1, 10, false, "RateLimitException"));
        listener.onRequestCompleted(new RequestCompleted("request", "backup", 20, true, 2));

        assertThat(started).containsExactly(
                "llm.router.request", "llm.router.select", "llm.router.attempt");
        assertThat(stopped).containsExactly(
                "llm.router.select", "llm.router.attempt", "llm.router.request");
    }

    @Test
    void collectsPromptOnlyThroughSanitizerAndRouteWhitelist() {
        AtomicReference<String> sanitizedInput = new AtomicReference<>();
        AtomicReference<String> observedPrompt = new AtomicReference<>();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override public boolean supportsContext(Observation.Context context) { return true; }
            @Override public void onStop(Observation.Context context) {
                context.getHighCardinalityKeyValues().forEach(keyValue -> {
                    if ("llm.router.prompt".equals(keyValue.getKey())) observedPrompt.set(keyValue.getValue());
                });
            }
        });
        MicrometerRouterObservationListener listener = new MicrometerRouterObservationListener(
                registry, new SimpleMeterRegistry(), false,
                false, true, false, false, true, 4, Set.of("allowed"), prompt -> {
                    sanitizedInput.set(prompt);
                    return "safe-value";
                });

        listener.onRequestStarted(new RequestStarted(
                "allowed-request", 1, ChatRequest.user("secret"), "allowed"));
        listener.onRequestCompleted(new RequestCompleted("allowed-request", null, 2, true, 0));
        assertThat(sanitizedInput.get()).isEqualTo("secret");
        assertThat(observedPrompt.get()).isEqualTo("safe");

        sanitizedInput.set(null);
        listener.onRequestStarted(new RequestStarted(
                "blocked-request", 1, ChatRequest.user("secret"), "blocked"));
        assertThat(sanitizedInput.get()).isNull();
    }
}
