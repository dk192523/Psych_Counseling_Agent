package com.dk.dkaiagent.account;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResourceBoundPasswordEncoderTest {
    @Test
    void encodeAndMatchesShareOneRateBudgetAndPreserveDelegateResults() {
        var clock = new MutableAuthClock();
        var limiter = new AuthResourceLimiter(clock, 10, 10, 10, 2, 1, 1000);
        var delegate = mock(PasswordEncoder.class);
        var encoder = new ResourceBoundPasswordEncoder(delegate, limiter);
        when(delegate.encode("secret")).thenReturn("encoded");
        when(delegate.matches("secret", "encoded")).thenReturn(true);

        assertEquals("encoded", encoder.encode("secret"));
        assertTrue(encoder.matches("secret", "encoded"));
        assertThrows(AuthResourceLimiter.CapacityExceededException.class, () -> encoder.encode("blocked"));
        assertThrows(AuthResourceLimiter.CapacityExceededException.class,
                () -> encoder.matches("blocked", "encoded"));
        verify(delegate).encode("secret");
        verify(delegate).matches("secret", "encoded");
        verifyNoMoreInteractions(delegate);

        clock.advance(Duration.ofSeconds(1));
        when(delegate.encode("new-secret")).thenReturn("new-encoded");
        assertFalse(encoder.matches("wrong", "encoded"));
        assertEquals("new-encoded", encoder.encode("new-secret"));
        assertThrows(AuthResourceLimiter.CapacityExceededException.class, () -> encoder.encode("blocked"));
        verify(delegate).matches("wrong", "encoded");
        verify(delegate).encode("new-secret");
        verifyNoMoreInteractions(delegate);
    }

    @Test
    void encodingInProgressUsesSameConcurrencyPermitAsMatching() throws Exception {
        var limiter = new AuthResourceLimiter(new MutableAuthClock(), 10, 10, 10, 2, 1, 1000);
        var delegate = mock(PasswordEncoder.class);
        var encoder = new ResourceBoundPasswordEncoder(delegate, limiter);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        when(delegate.encode("secret")).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return "encoded";
        });
        when(delegate.matches("secret", "encoded")).thenReturn(true);
        try {
            var encoding = pool.submit(() -> encoder.encode("secret"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            pool.submit(() -> assertThrows(AuthResourceLimiter.CapacityExceededException.class,
                    () -> encoder.matches("secret", "encoded"))).get(5, TimeUnit.SECONDS);
            verify(delegate, never()).matches(any(), anyString());
            release.countDown();
            assertEquals("encoded", encoding.get(5, TimeUnit.SECONDS));
            assertTrue(encoder.matches("secret", "encoded"));
            verify(delegate).matches("secret", "encoded");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void delegateExceptionsPropagateAndPermitIsReturnedForBothOperations() {
        var limiter = new AuthResourceLimiter(new MutableAuthClock(), 10, 10, 10, 4, 1, 1000);
        var delegate = mock(PasswordEncoder.class);
        var encoder = new ResourceBoundPasswordEncoder(delegate, limiter);
        var encodeFailure = new IllegalArgumentException("encode failed");
        var matchFailure = new IllegalStateException("match failed");
        when(delegate.encode("bad")).thenThrow(encodeFailure);
        when(delegate.matches("good", "encoded")).thenReturn(true);
        when(delegate.matches("bad", "encoded")).thenThrow(matchFailure);
        when(delegate.encode("good")).thenReturn("encoded");

        assertSame(encodeFailure, assertThrows(IllegalArgumentException.class, () -> encoder.encode("bad")));
        assertTrue(encoder.matches("good", "encoded"));
        assertSame(matchFailure,
                assertThrows(IllegalStateException.class, () -> encoder.matches("bad", "encoded")));
        assertEquals("encoded", encoder.encode("good"));
    }
}
