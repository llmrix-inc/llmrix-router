package com.llmrix.model.router.spring.boot.observability;

import com.llmrix.model.router.core.api.chat.ChatResponse;
import com.llmrix.model.router.core.engine.RoutedChatModel;
import com.llmrix.model.router.core.engine.RoutedChatModels;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RouterMetricsBinderTest {
    @Test
    void bindsCandidateRuntimeGauges() {
        try (RoutedChatModel route = RoutedChatModel.builder()
                .target("candidate", request -> ChatResponse.of("ok")).build();
             RoutedChatModels models = new RoutedChatModels(Map.of("general", route))) {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            new RouterMetricsBinder(models, true).bindTo(registry);

            assertThat(registry.get("llm.router.in.flight").tag("route", "general")
                    .tag("candidate", "candidate").gauge().value()).isZero();
            assertThat(registry.get("llm.router.available").tag("route", "general")
                    .tag("candidate", "candidate").gauge().value()).isEqualTo(1);
        }
    }
}
