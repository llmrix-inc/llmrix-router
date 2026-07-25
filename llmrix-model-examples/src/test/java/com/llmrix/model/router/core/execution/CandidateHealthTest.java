package com.llmrix.model.router.core.execution;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CandidateHealthTest {
    @Test
    void atomicallyLimitsConcurrentAttemptLeases() throws Exception {
        CandidateHealth health = new CandidateHealth();
        int contenders = 64;
        int limit = 7;
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(contenders);
        try {
            List<java.util.concurrent.Future<HealthAttempt>> futures = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return health.tryBeginAttempt(limit);
                }));
            }
            start.countDown();
            List<HealthAttempt> acquired = new ArrayList<>();
            for (var future : futures) {
                HealthAttempt attempt = future.get(2, TimeUnit.SECONDS);
                if (attempt != null) acquired.add(attempt);
            }

            assertEquals(limit, acquired.size());
            assertEquals(limit, health.inFlight());
            acquired.forEach(HealthAttempt::cancel);
            assertEquals(0, health.inFlight());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void settlesEachAttemptExactlyOnceUnderTerminalRaces() throws Exception {
        CandidateHealth health = new CandidateHealth();
        HealthAttempt attempt = health.tryBeginAttempt(1);
        int contenders = 64;
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(contenders);
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < contenders; index++) {
                int operation = index % 3;
                futures.add(executor.submit(() -> {
                    start.await();
                    if (operation == 0) attempt.cancel();
                    else if (operation == 1) attempt.success(1_000_000);
                    else attempt.failure(1_000_000, 2, java.time.Duration.ofSeconds(1));
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) future.get(2, TimeUnit.SECONDS);

            assertEquals(0, health.inFlight());
        } finally {
            executor.shutdownNow();
        }
    }
}
