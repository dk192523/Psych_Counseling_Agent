package com.dk.dkaiagent.memory;

/**
 * Published after a consolidation advanced the long-term digest and pruned the covered raw
 * messages. Listeners holding an in-process model window (e.g. CounselingApp) use it to drop the
 * stale window and rehydrate from the database on the next turn, without a direct dependency from
 * the history layer back into the conversation app.
 */
public record DigestAdvancedEvent(String chatId) {
}
