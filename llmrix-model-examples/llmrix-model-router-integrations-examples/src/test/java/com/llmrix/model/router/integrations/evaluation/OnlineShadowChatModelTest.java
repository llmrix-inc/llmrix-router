package com.llmrix.model.router.integrations.evaluation;

import com.llmrix.model.router.core.api.chat.ChatRequest;
import com.llmrix.model.router.core.api.chat.ChatResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.llmrix.model.router.core.api.chat.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;

class OnlineShadowChatModelTest {
    @Test
    void returnsPrimaryAndRunsShadowOutOfBand() throws Exception {
        CountDownLatch shadowed = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            OnlineShadowChatModel model = new OnlineShadowChatModel(
                    request -> ChatResponse.of("primary"),
                    Map.of("shadow", request -> { shadowed.countDown(); throw new IllegalStateException("ignored"); }),
                    1, 1, Duration.ofSeconds(1), executor, OnlineShadowListener.NOOP);

            assertThat(model.chat(ChatRequest.user("hello")).text()).isEqualTo("primary");
            assertThat(shadowed.await(1, TimeUnit.SECONDS)).isTrue();
        } finally { executor.shutdownNow(); }
    }

    @Test
    void skipsShadowForToolRequestsAndReportsTimeout() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch completed = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            OnlineShadowChatModel model = new OnlineShadowChatModel(
                    request -> ChatResponse.of("primary"),
                    Map.of("shadow", request -> { calls.incrementAndGet(); try { Thread.sleep(200); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } return ChatResponse.of("late"); }),
                    1, 1, Duration.ofMillis(20), executor,
                    (id, duration, success, error) -> completed.countDown());
            var toolRequest = ChatRequest.builder().userMessage("call tool")
                    .tools(java.util.List.of(new ToolDefinition("tool", "", Map.of()))).build();
            model.chat(toolRequest);
            Thread.sleep(40);
            assertThat(calls).hasValue(0);

            model.chat(ChatRequest.user("timeout"));
            assertThat(completed.await(1, TimeUnit.SECONDS)).isTrue();
        } finally { executor.shutdownNow(); }
    }
}
