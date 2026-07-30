package com.dk.dkaiagent.orchestration;

import java.util.Optional;
import java.util.function.Supplier;

/** Java 25 immutable request context with bounded lifetime. */
public final class ExecutionContextScope {

    private static final ScopedValue<AgentRequestContext> CURRENT = ScopedValue.newInstance();

    private ExecutionContextScope() {
    }

    public static <T> T call(AgentRequestContext context, Supplier<T> action) {
        return ScopedValue.where(CURRENT, context).call(action::get);
    }

    public static void run(AgentRequestContext context, Runnable action) {
        ScopedValue.where(CURRENT, context).run(action);
    }

    public static Optional<AgentRequestContext> current() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }

    public static AgentRequestContext requireCurrent() {
        return CURRENT.orElseThrow(() -> new IllegalStateException("agent request context is not bound"));
    }

    public static <T> Supplier<T> wrap(AgentRequestContext context, Supplier<T> action) {
        return () -> call(context, action);
    }
}
