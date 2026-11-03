package com.dk.dkaiagent.account;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AuthResourceLimiterTest {
    @Test
    void sourceBudgetIsIndependentAndResetsAtWindowBoundary() {
        var clock = new MutableAuthClock();
        var limiter = new AuthResourceLimiter(clock, 10, 2, 100, 100, 1, 1000);
        assertTrue(limiter.tryBeginLogin("192.0.2.1"));
        assertTrue(limiter.tryBeginLogin("192.0.2.1"));
        assertFalse(limiter.tryBeginLogin("192.0.2.1"));
        assertTrue(limiter.tryBeginLogin("192.0.2.2"));
        assertTrue(limiter.tryBeginLogin("192.0.2.2"));
        clock.advance(Duration.ofMillis(999));
        assertFalse(limiter.tryBeginLogin("192.0.2.1"));
        clock.advance(Duration.ofMillis(1));
        assertTrue(limiter.tryBeginLogin("192.0.2.1"));
    }

    @Test
    void missingSourcesShareOneBudget() {
        var limiter = new AuthResourceLimiter(new MutableAuthClock(), 10, 2, 100, 100, 1, 1000);
        assertTrue(limiter.tryBeginLogin(null));
        assertTrue(limiter.tryBeginLogin("  "));
        assertFalse(limiter.tryBeginLogin(""));
        assertFalse(limiter.tryBeginLogin(null));
        assertTrue(limiter.tryBeginLogin("192.0.2.1"));
    }

    @Test
    void rotatingSourcesCannotBypassGlobalLoginBudget() {
        var clock = new MutableAuthClock();
        var limiter = new AuthResourceLimiter(clock, 100, 10, 3, 100, 1, 1000);
        for (int i = 0; i < 3; i++) assertTrue(limiter.tryBeginLogin("source-" + i));
        for (int i = 3; i < 50; i++) assertFalse(limiter.tryBeginLogin("source-" + i));
        clock.advance(Duration.ofMillis(999));
        assertFalse(limiter.tryBeginLogin("fresh-source"));
        clock.advance(Duration.ofMillis(1));
        assertTrue(limiter.tryBeginLogin("fresh-source"));
    }

    @Test
    void rejectedSourcesDoNotSpendGlobalLoginBudget() {
        var limiter = new AuthResourceLimiter(new MutableAuthClock(), 10, 1, 2, 100, 1, 1000);
        assertFalse(limiter.tryBeginLogin("x".repeat(257)));
        assertTrue(limiter.tryBeginLogin("192.0.2.1"));
        for (int i = 0; i < 10; i++) assertFalse(limiter.tryBeginLogin("192.0.2.1"));
        assertTrue(limiter.tryBeginLogin("192.0.2.2"));
        assertFalse(limiter.tryBeginLogin("192.0.2.3"));
    }

    @Test
    void sourceKeyCapRetainsActiveBudgetsAndReclaimsExpiredKeys() {
        var clock = new MutableAuthClock();
        var limiter = new AuthResourceLimiter(clock, 2, 2, 1000, 100, 1, 1000);
        assertTrue(limiter.tryBeginLogin("192.0.2.1"));
        assertTrue(limiter.tryBeginLogin("192.0.2.1"));
        assertTrue(limiter.tryBeginLogin("192.0.2.2"));
        for (int i = 0; i < 100; i++) assertFalse(limiter.tryBeginLogin("random-" + i));
        assertFalse(limiter.tryBeginLogin("192.0.2.1"));
        assertTrue(limiter.tryBeginLogin("192.0.2.2"));
        clock.advance(Duration.ofMillis(999));
        limiter.expireIdleWindows();
        assertFalse(limiter.tryBeginLogin("192.0.2.3"));
        clock.advance(Duration.ofMillis(1));
        limiter.expireIdleWindows();
        assertTrue(limiter.tryBeginLogin("192.0.2.3"));
        assertTrue(limiter.tryBeginLogin("192.0.2.4"));
        assertFalse(limiter.tryBeginLogin("192.0.2.5"));
    }

    @Test
    void simultaneousLoginsCannotOverspendSourceOrGlobalBudget() throws Exception {
        assertEquals(3, loginBurst(true));
        assertEquals(5, loginBurst(false));
    }

    private int loginBurst(boolean sharedSource) throws Exception {
        var limiter = new AuthResourceLimiter(new MutableAuthClock(), 64, 3, 5, 100, 1, 1000);
        var pool = Executors.newFixedThreadPool(16);
        var ready = new CountDownLatch(16);
        var start = new CountDownLatch(1);
        List<Future<Boolean>> attempts = new ArrayList<>();
        try {
            for (int i = 0; i < 16; i++) {
                int index = i;
                attempts.add(pool.submit(() -> {
                    ready.countDown();
                    await(start);
                    return limiter.tryBeginLogin(sharedSource ? "192.0.2.1" : "source-" + index);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int accepted = 0;
            for (var attempt : attempts) if (attempt.get(5, TimeUnit.SECONDS)) accepted++;
            return accepted;
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void passwordWorkBudgetRejectsBeforeExecutingAndResetsAtBoundary() {
        var clock = new MutableAuthClock();
        var limiter = new AuthResourceLimiter(clock, 10, 10, 10, 2, 1, 1000);
        var executions = new AtomicInteger();
        assertEquals(1, limiter.withPasswordWork(executions::incrementAndGet));
        assertEquals(2, limiter.withPasswordWork(executions::incrementAndGet));
        assertThrows(AuthResourceLimiter.CapacityExceededException.class,
                () -> limiter.withPasswordWork(executions::incrementAndGet));
        clock.advance(Duration.ofMillis(999));
        assertThrows(AuthResourceLimiter.CapacityExceededException.class,
                () -> limiter.withPasswordWork(executions::incrementAndGet));
        assertEquals(2, executions.get());
        clock.advance(Duration.ofMillis(1));
        assertEquals(3, limiter.withPasswordWork(executions::incrementAndGet));
    }

    @Test
    void passwordConcurrencyRejectsExcessWorkWithoutSpendingItsRateBudget() throws Exception {
        var limiter = new AuthResourceLimiter(new MutableAuthClock(), 10, 10, 10, 3, 2, 1000);
        var pool = Executors.newFixedThreadPool(3);
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var executions = new AtomicInteger();
        List<Future<Integer>> running = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                running.add(pool.submit(() -> limiter.withPasswordWork(() -> {
                    int result = executions.incrementAndGet();
                    entered.countDown();
                    await(release);
                    return result;
                })));
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            pool.submit(() -> assertThrows(AuthResourceLimiter.CapacityExceededException.class,
                    () -> limiter.withPasswordWork(executions::incrementAndGet))).get(5, TimeUnit.SECONDS);
            assertEquals(2, executions.get());
            release.countDown();
            for (var task : running) task.get(5, TimeUnit.SECONDS);
            assertEquals(3, limiter.withPasswordWork(executions::incrementAndGet));
            assertThrows(AuthResourceLimiter.CapacityExceededException.class,
                    () -> limiter.withPasswordWork(executions::incrementAndGet));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void failedPasswordWorkReturnsConcurrencyPermitButStillCostsOneAttempt() {
        var limiter = new AuthResourceLimiter(new MutableAuthClock(), 10, 10, 10, 2, 1, 1000);
        var failure = new IllegalStateException("hash failed");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> limiter.withPasswordWork(() -> { throw failure; })));
        assertEquals("next", limiter.withPasswordWork(() -> "next"));
        assertThrows(AuthResourceLimiter.CapacityExceededException.class,
                () -> limiter.withPasswordWork(() -> "over budget"));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for test coordination");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while coordinating test", e);
        }
    }
}
