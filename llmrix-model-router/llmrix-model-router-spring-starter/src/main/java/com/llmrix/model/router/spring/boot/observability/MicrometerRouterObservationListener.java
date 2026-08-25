package com.llmrix.model.router.spring.boot.observability;

import com.llmrix.model.router.core.event.RouterListener;
import com.llmrix.model.router.core.event.AttemptCompleted;
import com.llmrix.model.router.core.event.RequestCompleted;
import com.llmrix.model.router.core.event.RequestStarted;
import com.llmrix.model.router.core.event.RouteSelected;
import com.llmrix.model.router.core.event.UsageRecorded;
import com.llmrix.model.router.core.event.TargetCooldown;
import com.llmrix.model.router.core.event.FirstTokenReceived;
import com.llmrix.model.router.core.event.AttemptStarted;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.Objects;

public final class MicrometerRouterObservationListener implements RouterListener {
    private final ObservationRegistry observations;
    private final MeterRegistry meters;
    private final boolean includeCandidateId;
    private final boolean metricsEnabled;
    private final boolean tracingEnabled;
    private final boolean includeCost;
    private final boolean includeRoutingReason;
    private final boolean includePrompts;
    private final int promptMaxChars;
    private final Set<String> promptRoutes;
    private final PromptSanitizer promptSanitizer;
    private final ConcurrentMap<String, Observation> active = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Observation> attempts = new ConcurrentHashMap<>();

    public MicrometerRouterObservationListener(ObservationRegistry observations, MeterRegistry meters, boolean includeCandidateId) {
        this(observations, meters, includeCandidateId, true, true, true, false);
    }

    public MicrometerRouterObservationListener(
            ObservationRegistry observations,
            MeterRegistry meters,
            boolean includeCandidateId,
            boolean metricsEnabled,
            boolean tracingEnabled) {
        this(observations, meters, includeCandidateId, metricsEnabled, tracingEnabled, true, false);
    }

    public MicrometerRouterObservationListener(
            ObservationRegistry observations,
            MeterRegistry meters,
            boolean includeCandidateId,
            boolean metricsEnabled,
            boolean tracingEnabled,
            boolean includeCost) {
        this(observations, meters, includeCandidateId, metricsEnabled, tracingEnabled, includeCost, false);
    }

    public MicrometerRouterObservationListener(
            ObservationRegistry observations, MeterRegistry meters, boolean includeCandidateId,
            boolean metricsEnabled, boolean tracingEnabled, boolean includeCost,
            boolean includeRoutingReason) {
        this(observations, meters, includeCandidateId, metricsEnabled, tracingEnabled, includeCost,
                includeRoutingReason, false, 1_024, Set.of(), null);
    }

    public MicrometerRouterObservationListener(
            ObservationRegistry observations, MeterRegistry meters, boolean includeCandidateId,
            boolean metricsEnabled, boolean tracingEnabled, boolean includeCost,
            boolean includeRoutingReason, boolean includePrompts, int promptMaxChars,
            Set<String> promptRoutes, PromptSanitizer promptSanitizer) {
        this.observations = observations;
        this.meters = meters;
        this.includeCandidateId = includeCandidateId;
        this.metricsEnabled = metricsEnabled;
        this.tracingEnabled = tracingEnabled;
        this.includeCost = includeCost;
        this.includeRoutingReason = includeRoutingReason;
        this.includePrompts = includePrompts;
        if (promptMaxChars < 1) throw new IllegalArgumentException("promptMaxChars must be > 0");
        this.promptMaxChars = promptMaxChars;
        this.promptRoutes = Set.copyOf(Objects.requireNonNull(promptRoutes, "promptRoutes"));
        this.promptSanitizer = promptSanitizer;
    }

    @Override
    public void onRequestStarted(RequestStarted event) {
        if (tracingEnabled) {
            Observation observation = Observation.start("llm.router.request", observations);
            observation.highCardinalityKeyValue("llm.request.id", event.requestId());
            if (includePrompts && promptSanitizer != null
                    && event.request() instanceof com.llmrix.model.router.core.api.chat.ChatRequest chatRequest
                    && event.route() != null && promptRoutes.contains(event.route())) {
                String prompt = chatRequest.messages().stream()
                        .filter(message -> "user".equals(message.role()))
                        .reduce((first, second) -> second).map(com.llmrix.model.router.core.api.chat.Message::content).orElse("");
                String sanitized = promptSanitizer.sanitize(prompt);
                if (sanitized != null) observation.highCardinalityKeyValue(
                        "llm.router.prompt", sanitized.substring(0, Math.min(promptMaxChars, sanitized.length())));
            }
            active.put(event.requestId(), observation);
        }
        if (metricsEnabled) meters.counter("llm.router.requests").increment();
    }

    @Override
    public void onRouteSelected(RouteSelected event) {
        Observation observation = active.get(event.requestId());
        if (observation != null) {
            observation.lowCardinalityKeyValue("llm.router.strategy", event.strategy());
            if (includeCandidateId) observation.lowCardinalityKeyValue("llm.router.target", event.targetId());
            if (includeRoutingReason && event.reason() != null) {
                observation.lowCardinalityKeyValue("llm.router.reason", event.reason());
            }
        }
        if (tracingEnabled) child("llm.router.select", event.requestId()).stop();
    }

    @Override
    public void onAttemptStarted(AttemptStarted event) {
        if (!tracingEnabled) return;
        Observation observation = child("llm.router.attempt", event.requestId())
                .lowCardinalityKeyValue("llm.router.attempt", Integer.toString(event.attempt()));
        if (includeCandidateId) observation.lowCardinalityKeyValue("llm.router.target", event.targetId());
        attempts.put(attemptKey(event.requestId(), event.targetId(), event.attempt()), observation);
    }

    @Override
    public void onAttemptCompleted(AttemptCompleted event) {
        Observation attempt = attempts.remove(attemptKey(event.requestId(), event.targetId(), event.attempt()));
        if (attempt != null) {
            if (!event.success()) attempt.error(new IllegalStateException(event.errorType()));
            attempt.stop();
        }
        if (!metricsEnabled) return;
        String candidate = includeCandidateId ? event.targetId() : "redacted";
        String errorType = event.errorType() == null ? "none" : event.errorType();
        meters.counter("llm.router.attempts", "candidate", candidate,
                "outcome", event.success() ? "success" : "failure", "error.type", errorType).increment();
        if (event.attempt() > 1) {
            meters.counter("llm.router.retries", "candidate", candidate).increment();
        }
    }

    @Override
    public void onFirstToken(FirstTokenReceived event) {
        if (!metricsEnabled) return;
        String candidate = includeCandidateId ? event.targetId() : "redacted";
        Timer.builder("llm.router.first.token")
                .tag("candidate", candidate).register(meters)
                .record(event.durationNanos(), TimeUnit.NANOSECONDS);
    }

    @Override
    public void onTargetCooldown(TargetCooldown event) {
        if (!metricsEnabled) return;
        String candidate = includeCandidateId ? event.targetId() : "redacted";
        meters.counter("llm.router.cooldowns", "candidate", candidate).increment();
    }

    @Override
    public void onUsageRecorded(UsageRecorded event) {
        if (!metricsEnabled) return;
        String candidate = includeCandidateId ? event.targetId() : "redacted";
        if (event.usage().inputTokens() >= 0) {
            meters.counter("llm.router.tokens", "candidate", candidate, "type", "input")
                    .increment(event.usage().inputTokens());
        }
        if (event.usage().outputTokens() >= 0) {
            meters.counter("llm.router.tokens", "candidate", candidate, "type", "output")
                    .increment(event.usage().outputTokens());
        }
        if (includeCost && Double.isFinite(event.estimatedCostUsd())) {
            meters.counter("llm.router.cost", "candidate", candidate)
                    .increment(event.estimatedCostUsd());
        }
    }

    @Override
    public void onRequestCompleted(RequestCompleted event) {
        if (metricsEnabled) {
            Timer.builder("llm.router.request.duration")
                    .tag("outcome", event.success() ? "success" : "failure")
                    .register(meters)
                    .record(event.durationNanos(), TimeUnit.NANOSECONDS);
        }
        Observation observation = active.remove(event.requestId());
        if (observation != null) {
            if (!event.success()) observation.error(new IllegalStateException("router request failed"));
            observation.stop();
        }
        attempts.forEach((key, attempt) -> {
            if (key.startsWith(event.requestId() + ":") && attempts.remove(key, attempt)) attempt.stop();
        });
    }

    private Observation child(String name, String requestId) {
        Observation parent = active.get(requestId);
        Observation child = Observation.createNotStarted(name, observations);
        if (parent != null) child.parentObservation(parent);
        return child.start();
    }

    private static String attemptKey(String requestId, String candidateId, int attempt) {
        return requestId + ":" + candidateId + ":" + attempt;
    }
}
