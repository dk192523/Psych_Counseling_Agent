package com.dk.dkaiagent.agent.counseling;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.deep-thinking")
public class DeepThinkingProperties {

    private boolean enabled = true;
    private int stepTimeoutSeconds = 45;
    private int historyMessages = 12;
    private int maxQueries = 3;
    private int associationHypotheses = 3;
    private int candidateTopK = 6;
    private double similarityThreshold = 0.25;
    private int candidateLimit = 12;
    private int evidenceLimit = 4;
    private int transcriptSnippetsPerCase = 2;
    private int candidateTextChars = 900;
    private int contextMaxChars = 8_000;
    private int vectorMaxConcurrency = 8;
    private int transcriptMaxConcurrency = 8;

    @PostConstruct
    void validate() {
        requirePositive(stepTimeoutSeconds, "step-timeout-seconds");
        requirePositive(historyMessages, "history-messages");
        requireAtMost(historyMessages, 30, "history-messages");
        requirePositive(maxQueries, "max-queries");
        requireAtMost(maxQueries, 8, "max-queries");
        requirePositive(associationHypotheses, "association-hypotheses");
        // Frozen contract caps association hypotheses at 3; keep a small hard ceiling for operators.
        requireAtMost(associationHypotheses, 5, "association-hypotheses");
        requirePositive(candidateTopK, "candidate-top-k");
        requirePositive(candidateLimit, "candidate-limit");
        requireAtMost(candidateLimit, 20, "candidate-limit");
        requirePositive(evidenceLimit, "evidence-limit");
        requireAtMost(evidenceLimit, 8, "evidence-limit");
        requirePositive(transcriptSnippetsPerCase, "transcript-snippets-per-case");
        requireAtMost(transcriptSnippetsPerCase, 3, "transcript-snippets-per-case");
        requirePositive(candidateTextChars, "candidate-text-chars");
        requireAtMost(candidateTextChars, 2_000, "candidate-text-chars");
        requirePositive(contextMaxChars, "context-max-chars");
        requirePositive(vectorMaxConcurrency, "vector-max-concurrency");
        requirePositive(transcriptMaxConcurrency, "transcript-max-concurrency");
        if (similarityThreshold < 0 || similarityThreshold > 1) {
            throw new IllegalArgumentException("app.deep-thinking.similarity-threshold must be between 0 and 1");
        }
        if (evidenceLimit > candidateLimit) {
            throw new IllegalArgumentException("app.deep-thinking.evidence-limit must not exceed candidate-limit");
        }
    }

    private static void requirePositive(int value, String property) {
        if (value <= 0) {
            throw new IllegalArgumentException("app.deep-thinking." + property + " must be greater than zero");
        }
    }

    private static void requireAtMost(int value, int maximum, String property) {
        if (value > maximum) {
            throw new IllegalArgumentException(
                    "app.deep-thinking." + property + " must not exceed " + maximum);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getStepTimeoutSeconds() {
        return stepTimeoutSeconds;
    }

    public void setStepTimeoutSeconds(int stepTimeoutSeconds) {
        this.stepTimeoutSeconds = stepTimeoutSeconds;
    }

    public int getHistoryMessages() {
        return historyMessages;
    }

    public void setHistoryMessages(int historyMessages) {
        this.historyMessages = historyMessages;
    }

    public int getMaxQueries() {
        return maxQueries;
    }

    public void setMaxQueries(int maxQueries) {
        this.maxQueries = maxQueries;
    }

    public int getAssociationHypotheses() {
        return associationHypotheses;
    }

    public void setAssociationHypotheses(int associationHypotheses) {
        this.associationHypotheses = associationHypotheses;
    }

    public int getCandidateTopK() {
        return candidateTopK;
    }

    public void setCandidateTopK(int candidateTopK) {
        this.candidateTopK = candidateTopK;
    }

    public double getSimilarityThreshold() {
        return similarityThreshold;
    }

    public void setSimilarityThreshold(double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
    }

    public int getCandidateLimit() {
        return candidateLimit;
    }

    public void setCandidateLimit(int candidateLimit) {
        this.candidateLimit = candidateLimit;
    }

    public int getEvidenceLimit() {
        return evidenceLimit;
    }

    public void setEvidenceLimit(int evidenceLimit) {
        this.evidenceLimit = evidenceLimit;
    }

    public int getTranscriptSnippetsPerCase() {
        return transcriptSnippetsPerCase;
    }

    public void setTranscriptSnippetsPerCase(int transcriptSnippetsPerCase) {
        this.transcriptSnippetsPerCase = transcriptSnippetsPerCase;
    }

    public int getCandidateTextChars() {
        return candidateTextChars;
    }

    public void setCandidateTextChars(int candidateTextChars) {
        this.candidateTextChars = candidateTextChars;
    }

    public int getContextMaxChars() {
        return contextMaxChars;
    }

    public void setContextMaxChars(int contextMaxChars) {
        this.contextMaxChars = contextMaxChars;
    }

    public int getVectorMaxConcurrency() {
        return vectorMaxConcurrency;
    }

    public void setVectorMaxConcurrency(int vectorMaxConcurrency) {
        this.vectorMaxConcurrency = vectorMaxConcurrency;
    }

    public int getTranscriptMaxConcurrency() {
        return transcriptMaxConcurrency;
    }

    public void setTranscriptMaxConcurrency(int transcriptMaxConcurrency) {
        this.transcriptMaxConcurrency = transcriptMaxConcurrency;
    }
}
