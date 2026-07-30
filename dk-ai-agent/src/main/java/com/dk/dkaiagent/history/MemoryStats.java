package com.dk.dkaiagent.history;

import java.time.Instant;

public record MemoryStats(
        int messageCount,
        int maxMessages,
        int digestedCount,
        int digestChars,
        String digest,
        Instant updatedAt
) {
}
