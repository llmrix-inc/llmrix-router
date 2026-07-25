package com.llmrix.model.router.core.spi;

import com.llmrix.model.router.core.spi.event.AttemptCompleted;
import com.llmrix.model.router.core.spi.event.FallbackStarted;
import com.llmrix.model.router.core.spi.event.RequestCompleted;
import com.llmrix.model.router.core.spi.event.RequestStarted;
import com.llmrix.model.router.core.spi.event.RouteSelected;
import com.llmrix.model.router.core.spi.event.UsageRecorded;
import com.llmrix.model.router.core.spi.event.CandidateCooldown;
import com.llmrix.model.router.core.spi.event.FirstTokenReceived;
import com.llmrix.model.router.core.spi.event.AttemptStarted;

public interface RouterListener {
    RouterListener NOOP = new RouterListener() {};

    default void onRequestStarted(RequestStarted event) {}
    default void onRouteSelected(RouteSelected event) {}
    default void onAttemptStarted(AttemptStarted event) {}
    default void onAttemptCompleted(AttemptCompleted event) {}
    default void onFallback(FallbackStarted event) {}
    default void onCandidateCooldown(CandidateCooldown event) {}
    default void onUsageRecorded(UsageRecorded event) {}
    default void onFirstToken(FirstTokenReceived event) {}
    default void onRequestCompleted(RequestCompleted event) {}
}
