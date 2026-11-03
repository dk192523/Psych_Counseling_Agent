package com.dk.dkaiagent.account;

import java.util.HashMap;
import java.util.Map;

/** Fixed windows with a hard key cap. Callers hold one lock across checking and consuming budgets. */
final class BoundedRateWindow {
    private record Window(int count, long start) { }
    private final Map<String, Window> windows = new HashMap<>();
    private final int maxKeys;
    private final int limit;
    private final long duration;
    private long nextCleanup;

    BoundedRateWindow(int maxKeys, int limit, long duration) {
        if (maxKeys < 1 || limit < 1 || duration < 1) throw new IllegalArgumentException("Invalid rate window");
        this.maxKeys = maxKeys;
        this.limit = limit;
        this.duration = duration;
    }

    boolean canConsume(String key, long now) {
        if (key == null || key.isBlank() || key.length() > 256) return false;
        cleanup(now);
        Window window = windows.get(key);
        if (window != null && now - window.start >= duration) {
            windows.remove(key);
            window = null;
        }
        // Never evict an active counter: cycling random keys must not reset another caller's budget.
        return window == null ? windows.size() < maxKeys : window.count < limit;
    }

    void consume(String key, long now) {
        Window window = windows.get(key);
        windows.put(key, window == null ? new Window(1, now) : new Window(window.count + 1, window.start));
    }

    void cleanup(long now) {
        if (now < nextCleanup) return;
        windows.values().removeIf(window -> now - window.start >= duration);
        nextCleanup = now + Math.min(duration, 60_000);
    }

    int size() { return windows.size(); }
}
