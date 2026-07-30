package com.dk.dkaiagent.history;

import java.time.Instant;

public record ConversationSummary(
        String id,
        String title,
        Instant createdAt,
        Instant updatedAt,
        int messageCount,
        String preview,
        int maxMessages
) {
}
