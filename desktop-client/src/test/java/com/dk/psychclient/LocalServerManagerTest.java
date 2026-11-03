package com.dk.psychclient;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class LocalServerManagerTest {
    @Test void failedCloseKeepsUiAndExecutorAvailableUntilAnExplicitSuccessfulRetry() {
        AtomicBoolean closing = new AtomicBoolean();
        QueuedExecutor lifecycle = new QueuedExecutor();
        Queue<Runnable> ui = new ConcurrentLinkedQueue<>();
        AtomicBoolean closeSucceeds = new AtomicBoolean();
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger exits = new AtomicInteger();
        BooleanSupplier close = () -> {
            attempts.incrementAndGet();
            return closeSucceeds.get();
        };
        Runnable request = () -> LocalServerManager.requestShutdown(closing, lifecycle, close, ui::add,
                failures::incrementAndGet, exits::incrementAndGet);

        IntStream.range(0, 32).parallel().forEach(ignored -> request.run());
        assertEquals(1, lifecycle.pending.size());
        lifecycle.runNext();
        assertEquals(1, attempts.get());
        assertFalse(lifecycle.isShutdown());
        assertEquals(0, exits.get());
        assertTrue(closing.get());
        request.run();
        assertTrue(lifecycle.pending.isEmpty());

        ui.remove().run();
        assertFalse(closing.get());
        assertEquals(1, failures.get());
        closeSucceeds.set(true);
        IntStream.range(0, 32).parallel().forEach(ignored -> request.run());
        assertEquals(1, lifecycle.pending.size());
        lifecycle.runNext();
        assertTrue(lifecycle.isShutdown());
        assertTrue(closing.get());
        assertEquals(2, attempts.get());
        assertEquals(0, exits.get());
        ui.remove().run();
        assertEquals(1, exits.get());

        request.run();
        assertTrue(lifecycle.pending.isEmpty());
        assertTrue(ui.isEmpty());
    }

    @Test void unexpectedCloseFailureAlsoRestoresRetryWithoutExiting() {
        AtomicBoolean closing = new AtomicBoolean();
        QueuedExecutor lifecycle = new QueuedExecutor();
        Queue<Runnable> ui = new ConcurrentLinkedQueue<>();
        AtomicInteger failures = new AtomicInteger();
        LocalServerManager.requestShutdown(closing, lifecycle, () -> {
            throw new IllegalStateException("simulated cleanup failure");
        }, ui::add, failures::incrementAndGet, () -> fail("must not exit after failed close"));

        lifecycle.runNext();
        assertFalse(lifecycle.isShutdown());
        ui.remove().run();
        assertFalse(closing.get());
        assertEquals(1, failures.get());
    }

    @Test void rejectedCleanupSubmissionDoesNotLeaveClosingLatched() {
        AtomicBoolean closing = new AtomicBoolean();
        QueuedExecutor lifecycle = new QueuedExecutor();
        lifecycle.shutdown();
        Queue<Runnable> ui = new ConcurrentLinkedQueue<>();
        AtomicInteger failures = new AtomicInteger();
        LocalServerManager.requestShutdown(closing, lifecycle, () -> fail("cleanup was rejected"),
                ui::add, failures::incrementAndGet, () -> fail("must not exit without confirmed close"));

        ui.remove().run();
        assertFalse(closing.get());
        assertEquals(1, failures.get());
    }

    private static final class QueuedExecutor extends AbstractExecutorService {
        private final Queue<Runnable> pending = new ConcurrentLinkedQueue<>();
        private volatile boolean shutdown;

        void runNext() { pending.remove().run(); }
        @Override public void execute(Runnable command) {
            if (shutdown) throw new RejectedExecutionException();
            pending.add(command);
        }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> remaining = new ArrayList<>(pending);
            pending.clear();
            return remaining;
        }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && pending.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }
}
