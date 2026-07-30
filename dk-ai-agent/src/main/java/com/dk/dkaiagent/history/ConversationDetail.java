package com.dk.dkaiagent.history;

import java.time.Instant;
import java.util.List;

public record ConversationDetail(
        String id,
        String title,
        Instant createdAt,
        Instant updatedAt,
        List<ConversationMessage> messages,
        MemoryStats memory
) {
}
