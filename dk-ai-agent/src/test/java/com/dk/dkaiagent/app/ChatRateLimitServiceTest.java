package com.dk.dkaiagent.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRateLimitServiceTest {

    @Test
    void allowsUpToLimitThenRejectsWithinWindow() {
        ChatRateLimitService service = new ChatRateLimitService(3, 60);

        assertTrue(service.tryAcquire(1L));
        assertTrue(service.tryAcquire(1L));
        assertTrue(service.tryAcquire(1L));
        assertFalse(service.tryAcquire(1L));
    }

    @Test
    void limitsArePerUser() {
        ChatRateLimitService service = new ChatRateLimitService(2, 60);

        assertTrue(service.tryAcquire(1L));
        assertTrue(service.tryAcquire(1L));
        assertFalse(service.tryAcquire(1L));
        // 另一个用户有独立窗口
        assertTrue(service.tryAcquire(2L));
    }

    @Test
    void windowExpiryRestoresCapacity() throws InterruptedException {
        ChatRateLimitService service = new ChatRateLimitService(1, 1);

        assertTrue(service.tryAcquire(1L));
        assertFalse(service.tryAcquire(1L));
        Thread.sleep(1100);
        assertTrue(service.tryAcquire(1L));
    }

    @Test
    void rejectsNonPositiveConfiguration() {
        boolean thrown = false;
        try {
            new ChatRateLimitService(0, 60);
        } catch (IllegalArgumentException expected) {
            thrown = true;
        }
        assertTrue(thrown);
    }
}
