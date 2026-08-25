package com.llmrix.model.router.core.event;

public interface RouterListener {
    RouterListener NOOP = new RouterListener() {
    };

    default void onRequestStarted(RequestStarted event) {
    }

    default void onRouteSelected(RouteSelected event) {
    }

    default void onAttemptStarted(AttemptStarted event) {
    }

    default void onAttemptCompleted(AttemptCompleted event) {
    }

    default void onTargetCooldown(TargetCooldown event) {
    }

    default void onUsageRecorded(UsageRecorded event) {
    }

    default void onFirstToken(FirstTokenReceived event) {
    }

    default void onRequestCompleted(RequestCompleted event) {
    }
}
