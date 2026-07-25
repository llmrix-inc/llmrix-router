package com.llmrix.model.router.integrations.fugu;

public interface FuguListener {
    FuguListener NOOP = new FuguListener() { };

    default void onStarted(FuguStarted event) { }
    default void onTurnStarted(FuguTurnStarted event) { }
    default void onTurnCompleted(FuguTurnCompleted event) { }
    default void onFallback(FuguFallback event) { }
    default void onCandidateCooldown(FuguCandidateCooldown event) { }
    default void onRetry(FuguRetry event) { }
    default void onCompleted(FuguCompleted event) { }
}
