package com.dk.dkaiagent.transfer;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** Business data only. Account secrets, permissions, sessions and vectors are absent by design. */
@JsonPropertyOrder({"formatVersion", "sourceNamespace", "schemaVersion", "users", "conversations",
        "messages", "memories", "turns", "tombstones"})
public record TransferSnapshot(
        int formatVersion,
        String sourceNamespace,
        String schemaVersion,
        List<User> users,
        List<Conversation> conversations,
        List<Message> messages,
        List<Memory> memories,
        List<Turn> turns,
        List<Tombstone> tombstones) {

    public static final int FORMAT_VERSION = 1;
    public static final String SCHEMA_VERSION = "psych-business-v1";

    public TransferSnapshot {
        users = TransferValidation.copyRecords(users);
        conversations = TransferValidation.copyRecords(conversations);
        messages = TransferValidation.copyRecords(messages);
        memories = TransferValidation.copyRecords(memories);
        turns = TransferValidation.copyRecords(turns);
        tombstones = TransferValidation.copyRecords(tombstones);
    }

    @JsonPropertyOrder({"id", "username"})
    public record User(long id, String username) {}

    @JsonPropertyOrder({"id", "title", "ownerId", "createdAt", "updatedAt"})
    public record Conversation(String id, String title, long ownerId, String createdAt, String updatedAt) {}

    @JsonPropertyOrder({"id", "conversationId", "role", "content", "clientMsgId", "createdAt"})
    public record Message(long id, String conversationId, String role, String content,
                          String clientMsgId, String createdAt) {}

    @JsonPropertyOrder({"conversationId", "digest", "coveredUntilMessageId", "coveredMessageCount",
            "digestChars", "updatedAt"})
    public record Memory(String conversationId, String digest, long coveredUntilMessageId,
                         int coveredMessageCount, int digestChars, String updatedAt) {}

    @JsonPropertyOrder({"conversationId", "clientMsgId", "requestHash", "status", "answer",
            "effectiveMode", "fallback", "updatedAt"})
    public record Turn(String conversationId, String clientMsgId, String requestHash, String status,
                       String answer, String effectiveMode, boolean fallback, String updatedAt) {}

    @JsonPropertyOrder({"conversationId", "ownerId", "deletedAt"})
    public record Tombstone(String conversationId, long ownerId, String deletedAt) {}
}
