package com.llmrix.model.orion.spring.boot.observability;

import com.llmrix.model.orion.observation.OrionModelClientListener;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

public final class MicrometerOrionModelClientListener implements OrionModelClientListener {
    private final ObservationRegistry observations;
    private final MeterRegistry meters;
    private final ConcurrentMap<String, Observation> active = new ConcurrentHashMap<>();

    public MicrometerOrionModelClientListener(ObservationRegistry observations, MeterRegistry meters) {
        this.observations = observations == null ? ObservationRegistry.NOOP : observations;
        this.meters = meters;
    }

    @Override public void onStarted(RequestStarted event) {
        Observation observation = Observation.start("llmrix.orion.request", observations)
                .lowCardinalityKeyValue("llm.operation", event.operation())
                .lowCardinalityKeyValue("llm.model", event.model());
        if (event.requestId() != null) {
            observation.highCardinalityKeyValue("llm.request.id", event.requestId());
        }
        active.put(event.invocationId(), observation);
        if (meters != null) meters.counter("llmrix.orion.requests", "operation", event.operation()).increment();
    }

    @Override public void onFirstToken(FirstToken event) {
        if (meters != null) Timer.builder("llmrix.orion.first.token").register(meters)
                .record(event.durationNanos(), TimeUnit.NANOSECONDS);
    }

    @Override public void onCompleted(RequestCompleted event) {
        Observation observation = active.remove(event.invocationId());
        if (observation != null) {
            if (!event.success()) observation.error(new IllegalStateException(event.errorType()));
            observation.stop();
        }
        if (meters != null) Timer.builder("llmrix.orion.request.duration")
                .tag("outcome", event.success() ? "success" : "failure")
                .tag("error.type", event.errorType() == null ? "none" : event.errorType())
                .register(meters).record(event.durationNanos(), TimeUnit.NANOSECONDS);
    }
}
