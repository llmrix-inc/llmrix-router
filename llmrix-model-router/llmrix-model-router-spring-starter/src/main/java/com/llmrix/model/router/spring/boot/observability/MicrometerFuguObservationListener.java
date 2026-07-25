package com.llmrix.model.router.spring.boot.observability;

import com.llmrix.model.router.integrations.fugu.FuguCompleted;
import com.llmrix.model.router.integrations.fugu.FuguListener;
import com.llmrix.model.router.integrations.fugu.FuguStarted;
import com.llmrix.model.router.integrations.fugu.FuguTurnCompleted;
import com.llmrix.model.router.integrations.fugu.FuguTurnStarted;
import com.llmrix.model.router.integrations.fugu.FuguFallback;
import com.llmrix.model.router.integrations.fugu.FuguCandidateCooldown;
import com.llmrix.model.router.integrations.fugu.FuguRetry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

public final class MicrometerFuguObservationListener implements FuguListener {
    private final ObservationRegistry observations;
    private final MeterRegistry meters;
    private final boolean includeCandidateId;
    private final boolean metricsEnabled;
    private final boolean tracingEnabled;
    private final ConcurrentMap<String, Observation> requests = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Observation> turns = new ConcurrentHashMap<>();

    public MicrometerFuguObservationListener(
            ObservationRegistry observations, MeterRegistry meters, boolean includeCandidateId) {
        this(observations, meters, includeCandidateId, true, true);
    }

    public MicrometerFuguObservationListener(
            ObservationRegistry observations, MeterRegistry meters, boolean includeCandidateId,
            boolean metricsEnabled, boolean tracingEnabled) {
        this.observations = observations;
        this.meters = meters;
        this.includeCandidateId = includeCandidateId;
        this.metricsEnabled = metricsEnabled;
        this.tracingEnabled = tracingEnabled;
    }

    @Override public void onStarted(FuguStarted event) {
        if (tracingEnabled) requests.put(event.requestId(), Observation.start("llm.fugu.orchestration", observations));
        if (metricsEnabled) meters.counter("llm.fugu.requests").increment();
    }

    @Override public void onTurnStarted(FuguTurnStarted event) {
        if (!tracingEnabled) return;
        Observation observation = Observation.start("llm.fugu.turn", observations)
                .lowCardinalityKeyValue("llm.fugu.role", event.role().name().toLowerCase());
        if (includeCandidateId) observation.lowCardinalityKeyValue("llm.fugu.candidate", event.candidateId());
        turns.put(turnKey(event.requestId(), event.turn()), observation);
    }

    @Override public void onTurnCompleted(FuguTurnCompleted event) {
        if (metricsEnabled) {
            String candidate = includeCandidateId ? event.candidateId() : "redacted";
            Timer.builder("llm.fugu.turn.duration")
                    .tag("candidate", candidate).tag("role", event.role().name().toLowerCase())
                    .register(meters).record(event.durationNanos(), TimeUnit.NANOSECONDS);
        }
        Observation observation = turns.remove(turnKey(event.requestId(), event.turn()));
        if (observation != null) observation.stop();
    }

    @Override public void onCompleted(FuguCompleted event) {
        if (metricsEnabled) Timer.builder("llm.fugu.orchestration.duration")
                    .tag("outcome", event.success() ? "success" : "failure")
                    .tag("termination", event.termination())
                    .register(meters).record(event.durationNanos(), TimeUnit.NANOSECONDS);
        Observation observation = requests.remove(event.requestId());
        if (observation != null) {
            observation.lowCardinalityKeyValue("llm.fugu.termination", event.termination());
            if (!event.success()) observation.error(new IllegalStateException(event.errorType()));
            observation.stop();
        }
        turns.forEach((key, turn) -> {
            if (key.startsWith(event.requestId() + ":") && turns.remove(key, turn)) {
                if (!event.success()) turn.error(new IllegalStateException(event.errorType()));
                turn.stop();
            }
        });
    }

    @Override public void onFallback(FuguFallback event) {
        if (metricsEnabled) meters.counter("llm.fugu.fallbacks").increment();
    }

    @Override public void onCandidateCooldown(FuguCandidateCooldown event) {
        if (!metricsEnabled) return;
        String candidate = includeCandidateId ? event.candidateId() : "redacted";
        meters.counter("llm.fugu.cooldowns", "candidate", candidate).increment();
    }

    @Override public void onRetry(FuguRetry event) {
        if (!metricsEnabled) return;
        String candidate = includeCandidateId ? event.candidateId() : "redacted";
        meters.counter("llm.fugu.retries", "candidate", candidate).increment();
    }

    private static String turnKey(String requestId, int turn) { return requestId + ":" + turn; }
}
