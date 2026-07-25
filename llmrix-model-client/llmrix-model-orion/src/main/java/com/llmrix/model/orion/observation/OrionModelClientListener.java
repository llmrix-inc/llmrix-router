package com.llmrix.model.orion.observation;

public interface OrionModelClientListener {
    OrionModelClientListener NOOP = new OrionModelClientListener() { };

    default void onStarted(RequestStarted event) { }
    default void onFirstToken(FirstToken event) { }
    default void onCompleted(RequestCompleted event) { }

    record RequestStarted(String invocationId, String requestId, String operation,
                          String model, long startedNanos) { }
    record FirstToken(String invocationId, long durationNanos) { }
    record RequestCompleted(String invocationId, long durationNanos,
                            boolean success, String errorType) { }
}
