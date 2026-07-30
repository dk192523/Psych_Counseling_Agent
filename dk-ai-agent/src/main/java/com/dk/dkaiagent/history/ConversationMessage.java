package com.dk.dkaiagent.history;

import java.time.Instant;

public record ConversationMessage(
        long id,
        String role,
        String content,
        Instant createdAt
) {
}
