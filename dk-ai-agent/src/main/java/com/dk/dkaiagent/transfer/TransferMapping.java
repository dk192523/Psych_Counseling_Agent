package com.dk.dkaiagent.transfer;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dk.dkaiagent.transfer.TransferException.Code.INVALID_MAPPING;

/** A reviewed, explicit identity binding; matching usernames never creates a binding. */
@JsonPropertyOrder({"formatVersion", "sourceNamespace", "entries"})
public record TransferMapping(int formatVersion, String sourceNamespace, List<Entry> entries) {
    public static final int FORMAT_VERSION = 1;

    public TransferMapping {
        entries = TransferValidation.copyRecords(entries);
    }

    @JsonPropertyOrder({"sourceUserId", "sourceUsername", "targetUserId", "targetUsername"})
    public record Entry(long sourceUserId, String sourceUsername, long targetUserId, String targetUsername) {}

    /**
     * Validate both ends against independently read account evidence. Every live source account
     * must have one entry; extra entries and many-to-one identity merges are rejected.
     */
    public Map<Long, Long> resolve(TransferSnapshot snapshot, Map<Long, String> targetUsers) {
        TransferFormat.validateSnapshot(snapshot);
        TransferFormat.validateMapping(this);
        if (targetUsers == null
                || !UUID.fromString(sourceNamespace).equals(UUID.fromString(snapshot.sourceNamespace()))
                || entries.size() != snapshot.users().size()) {
            throw new TransferException(INVALID_MAPPING);
        }
        Map<Long, String> sources = new HashMap<>();
        snapshot.users().forEach(user -> sources.put(user.id(), user.username()));
        Map<Long, Long> result = new HashMap<>();
        for (Entry entry : entries) {
            if (!entry.sourceUsername().equals(sources.get(entry.sourceUserId()))
                    || !entry.targetUsername().equals(targetUsers.get(entry.targetUserId()))) {
                throw new TransferException(INVALID_MAPPING);
            }
            result.put(entry.sourceUserId(), entry.targetUserId());
        }
        return Map.copyOf(result);
    }
}
