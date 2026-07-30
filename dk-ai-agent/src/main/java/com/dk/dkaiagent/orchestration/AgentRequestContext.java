package com.dk.dkaiagent.orchestration;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable control-plane context for one counseling turn. */
public record AgentRequestContext(
        String requestId,
        String conversationId,
        Instant deadline,
        String requestedMode) {

    public AgentRequestContext {
        requestId = requireText(requestId, "requestId");
        conversationId = requireText(conversationId, "conversationId");
        deadline = Objects.requireNonNull(deadline, "deadline");
        requestedMode = requireText(requestedMode, "requestedMode");
    }

    public static AgentRequestContext deep(String conversationId, Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be greater than zero");
        }
        return new AgentRequestContext(
                UUID.randomUUID().toString(),
                conversationId,
                Instant.now().plus(timeout),
                "deep"
        );
    }

    public Duration remaining() {
        Duration remaining = Duration.between(Instant.now(), deadline);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    public boolean expired() {
        return !Instant.now().isBefore(deadline);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
