package com.dk.dkaiagent.integration.aiworker;

import java.util.List;

public final class AiWorkerContracts {

    public static final String VERSION = "1";

    private AiWorkerContracts() {
    }

    public record HistoryMessage(String role, String content) {}

    public record PlanLimits(int maxQueries, int queryMaxChars, int maxMissingInformation) {}

    public record PlanRequest(
            String contractVersion,
            String requestId,
            String currentMessage,
            List<HistoryMessage> recentMessages,
            PlanLimits limits,
            String longTermDigest) {}

    public record PlanResponse(
            String contractVersion,
            String requestId,
            String stage,
            boolean shouldRetrieve,
            String focus,
            List<String> queries,
            List<String> missingInformation,
            String engine,
            boolean degraded,
            List<String> degradedReasons,
            long durationMs,
            List<String> associationHypotheses) {}

    public record Candidate(
            String id,
            String slug,
            String title,
            String text,
            double vectorScore) {}

    public record RefineLimits(int maxEvidence, int snippetsPerCase, int snippetMaxChars) {}

    public record RefineRequest(
            String contractVersion,
            String requestId,
            String currentMessage,
            String focus,
            List<String> queries,
            List<Candidate> candidates,
            RefineLimits limits) {}

    public record EvidenceSnippet(
            String start,
            String end,
            String text,
            String sourceUrl,
            double score) {}

    public record SelectedEvidence(
            String id,
            String slug,
            double rankScore,
            List<String> rankSignals,
            List<EvidenceSnippet> snippets) {}

    public record RefineResponse(
            String contractVersion,
            String requestId,
            List<SelectedEvidence> selectedEvidence,
            List<String> evidenceGaps,
            String engine,
            boolean degraded,
            List<String> degradedReasons,
            long durationMs) {}

    public record MemoryMessage(String role, String content, boolean safetyRelevant) {}

    public record ConsolidateLimits(int maxDigestChars) {}

    public record ConsolidateRequest(
            String contractVersion,
            String requestId,
            String existingDigest,
            List<MemoryMessage> messages,
            ConsolidateLimits limits) {}

    public record ConsolidateResponse(
            String contractVersion,
            String requestId,
            String digest,
            String engine,
            boolean degraded,
            List<String> degradedReasons,
            long durationMs) {}

    public record RecallCandidate(long id, String role, String content, double recencyScore) {}

    public record RecallLimits(int maxEpisodes, int snippetMaxChars) {}

    public record RecallRequest(
            String contractVersion,
            String requestId,
            String currentMessage,
            List<String> queries,
            List<RecallCandidate> candidates,
            RecallLimits limits) {}

    public record RecallEpisode(long id, String role, String snippet, double score) {}

    public record RecallResponse(
            String contractVersion,
            String requestId,
            List<RecallEpisode> episodes,
            String engine,
            boolean degraded,
            List<String> degradedReasons,
            long durationMs) {}
}
