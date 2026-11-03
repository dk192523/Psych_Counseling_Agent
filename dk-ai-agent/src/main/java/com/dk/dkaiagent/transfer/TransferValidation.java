package com.dk.dkaiagent.transfer;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dk.dkaiagent.transfer.TransferException.Code.*;

/** Shared validation stays independent of Spring, a DataSource and application startup. */
final class TransferValidation {
    private static final Set<String> ROLES = Set.of("user", "assistant", "system", "tool");
    private static final Set<String> STATUSES = Set.of("RUNNING", "COMPLETED", "FAILED", "INTERRUPTED");
    private static final Set<String> MODES = Set.of("standard", "deep");

    private TransferValidation() {}

    static <T> List<T> copyRecords(List<T> values) {
        if (values == null) throw new TransferException(INVALID_STRUCTURE);
        if (values.size() > TransferFormat.MAX_RECORDS) {
            throw new TransferException(LIMIT_EXCEEDED);
        }
        if (values.stream().anyMatch(value -> value == null)) throw new TransferException(INVALID_STRUCTURE);
        return List.copyOf(values);
    }

    static void snapshot(TransferSnapshot snapshot) {
        if (snapshot == null) throw new TransferException(INVALID_STRUCTURE);
        if (snapshot.formatVersion() != TransferSnapshot.FORMAT_VERSION
                || !TransferSnapshot.SCHEMA_VERSION.equals(snapshot.schemaVersion())) {
            throw new TransferException(UNSUPPORTED_VERSION);
        }
        namespace(snapshot.sourceNamespace());
        long total = (long) snapshot.users().size() + snapshot.conversations().size()
                + snapshot.messages().size() + snapshot.memories().size()
                + snapshot.turns().size() + snapshot.tombstones().size();
        if (total > TransferFormat.MAX_RECORDS) throw new TransferException(LIMIT_EXCEEDED);

        Set<Long> users = new HashSet<>();
        Set<String> usernames = new HashSet<>();
        for (var user : snapshot.users()) {
            positive(user.id());
            identifier(user.username(), 64);
            unique(users, user.id());
            unique(usernames, user.username());
        }

        Set<String> conversations = new HashSet<>();
        for (var conversation : snapshot.conversations()) {
            identifier(conversation.id(), 64);
            text(conversation.title());
            if (conversation.title().codePointCount(0, conversation.title().length()) > 120) {
                throw new TransferException(INVALID_VALUE);
            }
            positive(conversation.ownerId());
            reference(users, conversation.ownerId());
            instant(conversation.createdAt());
            instant(conversation.updatedAt());
            unique(conversations, conversation.id());
        }

        Map<Long, String> messageConversations = new HashMap<>();
        Set<Key> messageKeys = new HashSet<>();
        for (var message : snapshot.messages()) {
            positive(message.id());
            identifier(message.conversationId(), 64);
            reference(conversations, message.conversationId());
            text(message.role());
            if (!ROLES.contains(message.role())) throw new TransferException(INVALID_VALUE);
            text(message.content());
            instant(message.createdAt());
            if (messageConversations.putIfAbsent(message.id(), message.conversationId()) != null) {
                throw new TransferException(DUPLICATE_RECORD);
            }
            if (message.clientMsgId() != null) {
                identifier(message.clientMsgId(), 64);
                unique(messageKeys, new Key(message.conversationId(), message.clientMsgId()));
            }
        }

        Set<String> memories = new HashSet<>();
        for (var memory : snapshot.memories()) {
            identifier(memory.conversationId(), 64);
            reference(conversations, memory.conversationId());
            unique(memories, memory.conversationId());
            text(memory.digest());
            if (memory.coveredUntilMessageId() < 0 || memory.coveredMessageCount() < 0
                    || memory.digestChars() != memory.digest().length()) {
                throw new TransferException(INVALID_VALUE);
            }
            instant(memory.updatedAt());
            // Pruning normally removes the watermark row. Only an extant foreign row is invalid.
            String cursorConversation = messageConversations.get(memory.coveredUntilMessageId());
            if (cursorConversation != null && !cursorConversation.equals(memory.conversationId())) {
                throw new TransferException(INVALID_REFERENCE);
            }
        }

        Set<Key> turns = new HashSet<>();
        Set<String> runningConversations = new HashSet<>();
        for (var turn : snapshot.turns()) {
            identifier(turn.conversationId(), 64);
            reference(conversations, turn.conversationId());
            identifier(turn.clientMsgId(), 64);
            unique(turns, new Key(turn.conversationId(), turn.clientMsgId()));
            text(turn.requestHash());
            if (!turn.requestHash().matches("[0-9a-f]{64}")) throw new TransferException(INVALID_VALUE);
            text(turn.status());
            text(turn.effectiveMode());
            if (!STATUSES.contains(turn.status()) || !MODES.contains(turn.effectiveMode())) {
                throw new TransferException(INVALID_VALUE);
            }
            if ("RUNNING".equals(turn.status())) unique(runningConversations, turn.conversationId());
            text(turn.answer());
            instant(turn.updatedAt());
        }

        Set<String> tombstones = new HashSet<>();
        for (var tombstone : snapshot.tombstones()) {
            identifier(tombstone.conversationId(), 64);
            positive(tombstone.ownerId());
            instant(tombstone.deletedAt());
            unique(tombstones, tombstone.conversationId());
            if (conversations.contains(tombstone.conversationId())) throw new TransferException(INVALID_REFERENCE);
            // Tombstones intentionally outlive deleted accounts and have no account foreign key.
        }
    }

    static void mapping(TransferMapping mapping) {
        if (mapping == null) throw new TransferException(INVALID_STRUCTURE);
        if (mapping.formatVersion() != TransferMapping.FORMAT_VERSION) {
            throw new TransferException(UNSUPPORTED_VERSION);
        }
        namespace(mapping.sourceNamespace());
        Set<Long> sources = new HashSet<>();
        Set<Long> targets = new HashSet<>();
        Set<String> sourceNames = new HashSet<>();
        Set<String> targetNames = new HashSet<>();
        for (var entry : mapping.entries()) {
            positive(entry.sourceUserId());
            positive(entry.targetUserId());
            identifier(entry.sourceUsername(), 64);
            identifier(entry.targetUsername(), 64);
            unique(sources, entry.sourceUserId());
            unique(targets, entry.targetUserId());
            unique(sourceNames, entry.sourceUsername());
            unique(targetNames, entry.targetUsername());
        }
    }

    private static void namespace(String value) {
        text(value);
        try {
            if (!UUID.fromString(value).toString().equalsIgnoreCase(value)) {
                throw new TransferException(INVALID_VALUE);
            }
        } catch (IllegalArgumentException error) {
            throw new TransferException(INVALID_VALUE);
        }
    }

    private static void positive(long value) {
        if (value <= 0) throw new TransferException(INVALID_VALUE);
    }

    private static void identifier(String value, int maxChars) {
        text(value);
        if (value.isEmpty() || value.codePointCount(0, value.length()) > maxChars) {
            throw new TransferException(INVALID_VALUE);
        }
    }

    private static void instant(String value) {
        text(value);
        try {
            if (!value.endsWith("Z")) throw new TransferException(INVALID_VALUE);
            Instant.parse(value);
        } catch (DateTimeParseException error) {
            throw new TransferException(INVALID_VALUE);
        }
    }

    /** Check decoded UTF-8 size without allocating an additional copy of sensitive content. */
    private static void text(String value) {
        if (value == null) throw new TransferException(INVALID_VALUE);
        int bytes = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0) throw new TransferException(INVALID_VALUE); // PostgreSQL text excludes NUL.
            if (c < 0x80) bytes++;
            else if (c < 0x800) bytes += 2;
            else if (Character.isHighSurrogate(c)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new TransferException(INVALID_VALUE);
                }
                bytes += 4;
            } else if (Character.isLowSurrogate(c)) {
                throw new TransferException(INVALID_VALUE);
            } else bytes += 3;
            if (bytes > TransferFormat.MAX_TEXT_BYTES) throw new TransferException(LIMIT_EXCEEDED);
        }
    }

    private static <T> void unique(Set<T> values, T value) {
        if (!values.add(value)) throw new TransferException(DUPLICATE_RECORD);
    }

    private static <T> void reference(Set<T> values, T value) {
        if (!values.contains(value)) throw new TransferException(INVALID_REFERENCE);
    }

    private record Key(String conversationId, String clientMsgId) {}
}
