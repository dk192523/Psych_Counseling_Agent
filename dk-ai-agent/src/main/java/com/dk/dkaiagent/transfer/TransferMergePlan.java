package com.dk.dkaiagent.transfer;

/** Safe to print: only namespace, commitments and aggregate counts, never account names or text. */
public record TransferMergePlan(
        int formatVersion,
        String sourceNamespace,
        String snapshotSha256,
        String mappingSha256,
        String targetIdentitySha256,
        String mappedAccountsSha256,
        boolean allowRunningToFailed,
        Counts counts,
        String planSha256,
        boolean alreadyImported) {

    public record Counts(long mappedUsers, long conversations, long messages, long memories,
                         long turns, long tombstones, long reservedDeletedOwners,
                         long watermarkReservations, long runningTurnsConverted) {}
}
