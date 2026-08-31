package com.dk.dkaiagent.history;

/**
 * Signals that a conversation is absent, belongs to another user, or was deleted
 * while a request was in flight. Controllers intentionally expose all three cases
 * as the same 404 response so callers cannot probe another user's conversation.
 */
public final class ConversationUnavailableException extends IllegalStateException {

    public ConversationUnavailableException(String message) {
        super(message);
    }
}
