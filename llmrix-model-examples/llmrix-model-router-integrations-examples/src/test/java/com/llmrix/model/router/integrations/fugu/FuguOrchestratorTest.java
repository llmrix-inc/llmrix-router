package com.llmrix.model.router.integrations.fugu;

import com.llmrix.model.router.core.api.chat.ChatModel;
import com.llmrix.model.router.core.api.chat.ChatResponse;
import com.llmrix.model.router.core.api.chat.ChatRequest;
import com.llmrix.model.router.core.api.chat.ResponseFormat;
import com.llmrix.model.router.core.api.chat.ToolChoice;
import com.llmrix.model.router.core.api.chat.ToolDefinition;
import com.llmrix.model.router.core.exception.BudgetExceededException;
import com.llmrix.model.router.core.exception.ModelTimeoutException;
import com.llmrix.model.router.core.exception.RateLimitException;
import com.llmrix.model.router.core.model.ModelPricing;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.Optional;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FuguOrchestratorTest {

    @Test
    void sharesCooldownThroughRouterStateStore() {
        var store = new com.llmrix.model.router.core.state.InMemoryRouterStateStore();
        AtomicInteger calls = new AtomicInteger();
        ChatModel failing = request -> { calls.incrementAndGet(); throw new IllegalStateException("failed"); };
        java.util.function.Supplier<FuguOrchestrator> factory = () -> FuguOrchestrator.builder()
                .candidate("shared", failing)
                .router(state -> new FuguAction("shared", FuguRole.WORKER))
                .maxTurns(1).cooldown(Duration.ofMinutes(1)).fallbackOn(error -> true)
                .stateStore(store).stateNamespace("shared-fugu").build();

        assertThrows(IllegalStateException.class, () -> factory.get().chat(ChatRequest.user("first")));
        assertThrows(com.llmrix.model.router.core.exception.ModelUnavailableException.class,
                () -> factory.get().chat(ChatRequest.user("second")));
        assertEquals(1, calls.get());
    }

    @Test
    void solvesThenVerifies() {
        ChatModel solver = request -> ChatResponse.of("answer 42");
        ChatModel verifier = request -> ChatResponse.of("ACCEPT complete");
        FuguOrchestrator orchestrator = FuguOrchestrator.builder()
                .candidate("solver", solver)
                .candidate("verifier", verifier)
                .router(FuguRouters.workerThenVerifier("solver", "verifier"))
                .build();

        ChatResponse response = orchestrator.chat("question");

        assertEquals("answer 42", response.text());
        assertEquals("verifier-accept", response.metadata().get("fugu.termination"));
        assertEquals(2, ((List<?>) response.metadata().get("fugu.turns")).size());
    }

    @Test
    void thinkerSuggestionIsPassedToWorker() {
        ChatModel thinker = request -> ChatResponse.of("check edge cases");
        ChatModel worker = request -> {
            String prompt = request.messages().get(request.messages().size() - 1).content();
            assertTrue(prompt.contains("check edge cases"));
            return ChatResponse.of("improved answer");
        };
        ChatModel verifier = request -> ChatResponse.of("ACCEPT");
        FuguRouter router = state -> switch (state.turns().size()) {
            case 0 -> new FuguAction("thinker", FuguRole.THINKER);
            case 1 -> new FuguAction("worker", FuguRole.WORKER);
            default -> new FuguAction("verifier", FuguRole.VERIFIER);
        };
        FuguOrchestrator orchestrator = FuguOrchestrator.builder()
                .candidate("thinker", thinker)
                .candidate("worker", worker)
                .candidate("verifier", verifier)
                .router(router)
                .build();

        assertEquals("improved answer", orchestrator.chat("question").text());
    }

    @Test
    void enforcesTotalTimeout() {
        ChatModel slow = request -> {
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return ChatResponse.of("late");
        };
        FuguOrchestrator orchestrator = FuguOrchestrator.builder()
                .candidate("slow", slow)
                .router(state -> new FuguAction("slow", FuguRole.WORKER))
                .timeout(Duration.ofMillis(20))
                .build();

        assertThrows(ModelTimeoutException.class, () -> orchestrator.chat("question"));
    }

    @Test
    void enforcesCumulativeTokenBudget() {
        ChatModel verbose = request -> ChatResponse.of("this response exceeds the configured budget");
        FuguOrchestrator orchestrator = FuguOrchestrator.builder()
                .candidate("verbose", verbose)
                .router(state -> new FuguAction("verbose", FuguRole.WORKER))
                .tokenBudget(2)
                .build();

        assertThrows(BudgetExceededException.class, () -> orchestrator.chat("question"));
    }

    @Test
    void supportsCustomStopCondition() {
        FuguOrchestrator orchestrator = FuguOrchestrator.builder()
                .candidate("worker", request -> ChatResponse.of("good enough"))
                .router(state -> new FuguAction("worker", FuguRole.WORKER))
                .stopCondition(state -> state.latestAnswer() != null
                        ? Optional.of("quality-threshold") : Optional.empty())
                .build();

        ChatResponse response = orchestrator.chat("question");
        assertEquals("quality-threshold", response.metadata().get("fugu.termination"));
        assertEquals(1, ((List<?>) response.metadata().get("fugu.turns")).size());
    }

    @Test
    void supportsCustomRolePromptTemplate() {
        ChatModel worker = request -> {
            assertEquals("custom-worker", request.messages().get(0).content());
            return ChatResponse.of("done");
        };
        FuguOrchestrator orchestrator = FuguOrchestrator.builder()
                .candidate("worker", worker)
                .router(state -> new FuguAction("worker", FuguRole.WORKER))
                .promptTemplate((original, role, answer, suggestion) -> ChatRequest.user("custom-worker"))
                .maxTurns(1)
                .build();

        assertEquals("done", orchestrator.chat("question").text());
    }

    @Test
    void defaultPromptTemplatePreservesRequestCapabilities() {
        ToolDefinition tool = new ToolDefinition("weather", null, Map.of("type", "object"));
        ChatModel worker = request -> {
            assertEquals(1, request.tools().size());
            assertEquals(ToolChoice.Mode.AUTO, request.toolChoice().mode());
            assertEquals(ResponseFormat.Type.JSON_OBJECT, request.responseFormat().type());
            assertEquals(0.2, request.generationOptions().temperature());
            return ChatResponse.of("done");
        };
        FuguOrchestrator orchestrator = FuguOrchestrator.builder()
                .candidate("worker", worker)
                .router(state -> new FuguAction("worker", FuguRole.WORKER))
                .maxTurns(1)
                .build();

        orchestrator.chat(ChatRequest.builder().userMessage("weather")
                .tools(tool).toolChoice(ToolChoice.auto()).responseFormat(ResponseFormat.jsonObject())
                .generationOptions(com.llmrix.model.router.core.api.chat.GenerationOptions.builder().temperature(0.2).build())
                .build());
    }

    @Test
    void supportsNativeAsyncCancellation() {
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.ExecutorService asyncExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            FuguOrchestrator orchestrator = FuguOrchestrator.builder()
                    .candidate("slow", request -> {
                        try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        return ChatResponse.of("late");
                    })
                    .router(state -> new FuguAction("slow", FuguRole.WORKER))
                    .executor(executor)
                    .asyncExecutor(asyncExecutor)
                    .build();
            java.util.concurrent.CompletableFuture<ChatResponse> result = orchestrator
                    .chatAsync(ChatRequest.user("question")).toCompletableFuture();
            result.cancel(true);
            assertTrue(result.isCancelled());
        } finally {
            executor.shutdownNow();
            asyncExecutor.shutdownNow();
        }
    }

    @Test
    void streamsCompletedResultWithBackpressure() throws Exception {
        FuguOrchestrator orchestrator = FuguOrchestrator.builder()
                .candidate("worker", request -> ChatResponse.of("final answer"))
                .router(state -> new FuguAction("worker", FuguRole.WORKER))
                .maxTurns(1).build();
        List<com.llmrix.model.router.core.api.chat.ChatChunk> chunks = new CopyOnWriteArrayList<>();
        AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        CountDownLatch firstChunk = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);

        orchestrator.stream(ChatRequest.user("question")).subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription value) {
                subscription.set(value);
                value.request(1);
            }
            @Override public void onNext(com.llmrix.model.router.core.api.chat.ChatChunk item) {
                chunks.add(item);
                firstChunk.countDown();
            }
            @Override public void onError(Throwable throwable) { completed.countDown(); }
            @Override public void onComplete() { completed.countDown(); }
        });

        assertTrue(firstChunk.await(2, TimeUnit.SECONDS));
        assertEquals(List.of("final answer"), chunks.stream().map(com.llmrix.model.router.core.api.chat.ChatChunk::text).toList());
        assertEquals(1, completed.getCount());
        subscription.get().request(1);
        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertEquals(List.of("final answer", ""), chunks.stream().map(com.llmrix.model.router.core.api.chat.ChatChunk::text).toList());
        assertTrue(chunks.get(1).finished());
    }

    @Test
    void cancellingDeferredStreamInterruptsOrchestration() throws Exception {
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        var asyncExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        try {
            FuguOrchestrator orchestrator = FuguOrchestrator.builder()
                    .candidate("slow", request -> {
                        entered.countDown();
                        try { Thread.sleep(5_000); }
                        catch (InterruptedException error) { interrupted.countDown(); Thread.currentThread().interrupt(); }
                        return ChatResponse.of("late");
                    })
                    .router(state -> new FuguAction("slow", FuguRole.WORKER))
                    .executor(executor).asyncExecutor(asyncExecutor).maxTurns(1).build();
            orchestrator.stream(ChatRequest.user("question")).subscribe(new Flow.Subscriber<>() {
                @Override public void onSubscribe(Flow.Subscription value) {
                    subscription.set(value);
                    value.request(1);
                }
                @Override public void onNext(com.llmrix.model.router.core.api.chat.ChatChunk item) { }
                @Override public void onError(Throwable throwable) { }
                @Override public void onComplete() { }
            });

            assertTrue(entered.await(2, TimeUnit.SECONDS));
            subscription.get().cancel();
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            asyncExecutor.shutdownNow();
        }
    }

    @Test
    void retriesRetryableCandidateFailure() {
        AtomicInteger attempts = new AtomicInteger();
        ChatModel flaky = request -> {
            if (attempts.incrementAndGet() == 1) throw new RateLimitException("limited");
            return ChatResponse.of("recovered");
        };
        FuguOrchestrator orchestrator = FuguOrchestrator.builder()
                .candidate("flaky", flaky)
                .router(state -> new FuguAction("flaky", FuguRole.WORKER))
                .maxRetries(1).maxTurns(1)
                .build();

        assertEquals("recovered", orchestrator.chat("question").text());
        assertEquals(2, attempts.get());
    }

    @Test
    void fallsBackAndRecordsActualCandidate() {
        FuguOrchestrator orchestrator = FuguOrchestrator.builder()
                .candidate("primary", request -> { throw new RateLimitException("limited"); })
                .candidate("backup", request -> ChatResponse.of("backup answer"))
                .router(state -> new FuguAction("primary", FuguRole.WORKER))
                .fallbacks("primary", "backup")
                .maxTurns(1)
                .build();

        ChatResponse response = orchestrator.chat("question");

        assertEquals("backup answer", response.text());
        assertTrue(response.metadata().get("fugu.turns").toString().contains("backup"));
    }

    @Test
    void skipsCandidateDuringCooldown() {
        AtomicInteger primaryAttempts = new AtomicInteger();
        FuguOrchestrator orchestrator = FuguOrchestrator.builder()
                .candidate("primary", request -> {
                    primaryAttempts.incrementAndGet();
                    throw new RateLimitException("limited");
                })
                .candidate("backup", request -> ChatResponse.of("backup"))
                .router(state -> new FuguAction("primary", FuguRole.WORKER))
                .fallbacks("primary", "backup")
                .cooldown(Duration.ofMinutes(1))
                .maxTurns(1)
                .build();

        orchestrator.chat("first");
        orchestrator.chat("second");

        assertEquals(1, primaryAttempts.get());
    }

    @Test
    void enforcesCumulativeCostBudgetBeforeCallingCandidate() {
        AtomicInteger calls = new AtomicInteger();
        FuguOrchestrator orchestrator = FuguOrchestrator.builder()
                .candidate("paid", request -> { calls.incrementAndGet(); return ChatResponse.of("answer"); },
                        new ModelPricing(1.0, 1.0))
                .router(state -> new FuguAction("paid", FuguRole.WORKER))
                .maxCostUsd(0.0001)
                .build();

        assertThrows(BudgetExceededException.class, () -> orchestrator.chat("question"));
        assertEquals(0, calls.get());
    }

    @Test
    void costBudgetRequiresKnownPricing() {
        assertThrows(IllegalArgumentException.class, () -> FuguOrchestrator.builder()
                .candidate("unknown", request -> ChatResponse.of("answer"))
                .router(state -> new FuguAction("unknown", FuguRole.WORKER))
                .maxCostUsd(1)
                .build());
    }

    @Test
    void publishesFuguLifecycleEvents() {
        List<String> events = new ArrayList<>();
        FuguListener listener = new FuguListener() {
            @Override public void onStarted(FuguStarted event) { events.add("start:" + event.requestId()); }
            @Override public void onTurnCompleted(FuguTurnCompleted event) { events.add("turn:" + event.requestId()); }
            @Override public void onCompleted(FuguCompleted event) { events.add("complete:" + event.requestId()); }
        };
        FuguOrchestrator orchestrator = FuguOrchestrator.builder()
                .candidate("worker", request -> ChatResponse.of("done"))
                .router(state -> new FuguAction("worker", FuguRole.WORKER))
                .maxTurns(1)
                .listener(listener)
                .build();

        orchestrator.chat("question");

        assertEquals(3, events.size());
        assertEquals(events.get(0).substring("start:".length()), events.get(1).substring("turn:".length()));
        assertEquals(events.get(0).substring("start:".length()), events.get(2).substring("complete:".length()));
    }

    @Test
    void publishesFailedCompletionEvent() {
        List<FuguCompleted> completed = new ArrayList<>();
        FuguListener listener = new FuguListener() {
            @Override public void onCompleted(FuguCompleted event) { completed.add(event); }
        };
        FuguOrchestrator orchestrator = FuguOrchestrator.builder()
                .candidate("broken", request -> { throw new IllegalStateException("failed"); })
                .router(state -> new FuguAction("broken", FuguRole.WORKER))
                .listener(listener)
                .build();

        assertThrows(IllegalStateException.class, () -> orchestrator.chat("question"));
        assertEquals(1, completed.size());
        assertEquals(false, completed.get(0).success());
        assertEquals("IllegalStateException", completed.get(0).errorType());
    }
}
