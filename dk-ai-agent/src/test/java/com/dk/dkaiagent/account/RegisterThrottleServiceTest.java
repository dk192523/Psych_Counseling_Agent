package com.dk.dkaiagent.account;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class RegisterThrottleServiceTest {
    @Test
    void randomUsernamesStillConsumeOneIpBudget() {
        var throttle = new RegisterThrottleService();
        for (int i = 0; i < RegisterThrottleService.IP_WINDOW_MAX; i++) {
            assertTrue(throttle.tryBeginRegister("192.0.2.1", "user" + i));
        }
        assertFalse(throttle.tryBeginRegister("192.0.2.1", "another"));
        assertTrue(throttle.tryBeginRegister("192.0.2.2", "another"));
    }

    @Test
    void normalizedUsernameBudgetSpansIpsAndRejectConsumesNeitherBudget() {
        var throttle = new RegisterThrottleService();
        for (int i = 0; i < RegisterThrottleService.USERNAME_WINDOW_MAX; i++) {
            assertTrue(throttle.tryBeginRegister("192.0.2." + i, " alice "));
        }
        assertFalse(throttle.tryBeginRegister("192.0.2.90", "alice"));
        for (int i = 0; i < RegisterThrottleService.IP_WINDOW_MAX; i++) {
            assertTrue(throttle.tryBeginRegister("192.0.2.90", "fresh" + i));
        }
    }

    @Test
    void parallelAdmissionNeverOverspendsEitherDimension() throws Exception {
        assertEquals(RegisterThrottleService.IP_WINDOW_MAX, burst(false));
        assertEquals(RegisterThrottleService.USERNAME_WINDOW_MAX, burst(true));
    }

    private int burst(boolean sharedUsername) throws Exception {
        var throttle = new RegisterThrottleService();
        var pool = Executors.newFixedThreadPool(32);
        var start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 32; i++) {
                int index = i;
                results.add(pool.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return throttle.tryBeginRegister(sharedUsername ? "192.0.2." + index : "192.0.2.1",
                            sharedUsername ? "alice" : "user" + index);
                }));
            }
            start.countDown();
            int admitted = 0;
            for (var result : results) if (result.get(5, TimeUnit.SECONDS)) admitted++;
            return admitted;
        } finally { pool.shutdownNow(); }
    }

    @Test
    void hardKeyCapDoesNotEvictActiveCountersAndExpiredKeysAreReclaimed() {
        var clock = new MutableAuthClock();
        var throttle = new RegisterThrottleService(clock, 2, 1000);
        assertTrue(throttle.tryBeginRegister("192.0.2.1", "alice"));
        assertTrue(throttle.tryBeginRegister("192.0.2.2", "bob"));
        for (int i = 0; i < 100; i++) assertFalse(throttle.tryBeginRegister("random" + i, "user" + i));
        assertEquals(2, throttle.ipCount());
        assertEquals(2, throttle.usernameCount());
        clock.advance(Duration.ofSeconds(2));
        throttle.expireIdleWindows();
        assertEquals(0, throttle.ipCount());
        assertEquals(0, throttle.usernameCount());
        assertTrue(throttle.tryBeginRegister("192.0.2.3", "carol"));
    }

    @Test
    void invalidKeysCannotBypassAdmissionOrAllocateState() {
        var throttle = new RegisterThrottleService();
        assertFalse(throttle.tryBeginRegister(null, "alice"));
        assertFalse(throttle.tryBeginRegister("192.0.2.1", "  "));
        assertFalse(throttle.tryBeginRegister("192.0.2.1", "x".repeat(1000)));
        assertEquals(0, throttle.ipCount());
        assertEquals(0, throttle.usernameCount());
    }
}
