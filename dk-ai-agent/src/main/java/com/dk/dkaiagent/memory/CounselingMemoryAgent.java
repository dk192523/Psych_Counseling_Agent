package com.dk.dkaiagent.memory;

import java.util.List;

/**
 * Provider-neutral counseling memory agent contract.
 * A Hermes sidecar can implement the same contract without changing the HTTP or Vue layers,
 * mirroring the design of {@code CounselingAgentExecutor} in the counseling agent package.
 */
public interface CounselingMemoryAgent {

    /**
     * Folds the given messages into the existing long-term digest.
     *
     * @return an outcome with {@code success=false} when no engine produced a usable digest;
     *         implementations must never throw into the caller.
     */
    ConsolidationOutcome consolidate(String existingDigest, List<MemoryInput> messages, int maxDigestChars);

    record MemoryInput(String role, String content, boolean safetyRelevant) {
    }

    record ConsolidationOutcome(boolean success, String digest, String engine) {
    }
}
