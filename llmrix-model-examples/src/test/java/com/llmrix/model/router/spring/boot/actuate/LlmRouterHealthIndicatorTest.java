package com.llmrix.model.router.spring.boot.actuate;

import com.llmrix.model.router.core.api.ChatResponse;
import com.llmrix.model.router.core.api.RoutedChatModel;
import com.llmrix.model.router.core.api.RoutedChatModels;
import com.llmrix.model.router.core.exception.RateLimitException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmRouterHealthIndicatorTest {

    @Test
    void reportsUpWhenEveryRouteHasAnAvailableCandidate() {
        try (RoutedChatModels models = new RoutedChatModels(Map.of(
                "general", RoutedChatModel.of(request -> ChatResponse.of("ok"))))) {
            var health = new LlmRouterHealthIndicator(models).health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsEntry("availableRoutes", 1);
        }
    }

    @Test
    void reportsDownWhenAllCandidatesAreCoolingDown() {
        try (RoutedChatModel route = RoutedChatModel.builder()
                .candidate("failing", request -> { throw new RateLimitException("limited"); })
                .maxRetries(0)
                .failureThreshold(1)
                .cooldown(Duration.ofMinutes(1))
                .build();
             RoutedChatModels models = new RoutedChatModels(Map.of("general", route))) {
            try { route.chat("trigger cooldown"); } catch (RateLimitException ignored) { }

            var health = new LlmRouterHealthIndicator(models).health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails().toString()).contains("cooldown");
        }
    }
}
