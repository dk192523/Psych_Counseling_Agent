package com.dk.dkaiagent.memory;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.chat-history.memory")
public class MemoryProperties {

    private boolean enabled = true;
    private int digestMaxChars = 1_200;
    private int foldThresholdMessages = 6;
    private int recallCandidates = 30;
    private int recallMaxEpisodes = 4;
    private int recallSnippetChars = 300;

    @PostConstruct
    void validate() {
        // Boundaries mirror the frozen worker contract: digest 200..3000, episodes 1..8,
        // snippet 80..800, consolidate/recall lists capped at 60.
        requireAtLeast(digestMaxChars, 200, "digest-max-chars");
        requireAtMost(digestMaxChars, 3_000, "digest-max-chars");
        requirePositive(foldThresholdMessages, "fold-threshold-messages");
        requireAtMost(foldThresholdMessages, 60, "fold-threshold-messages");
        requirePositive(recallCandidates, "recall-candidates");
        requireAtMost(recallCandidates, 60, "recall-candidates");
        requirePositive(recallMaxEpisodes, "recall-max-episodes");
        requireAtMost(recallMaxEpisodes, 8, "recall-max-episodes");
        requireAtLeast(recallSnippetChars, 80, "recall-snippet-chars");
        requireAtMost(recallSnippetChars, 800, "recall-snippet-chars");
    }

    private static void requirePositive(int value, String property) {
        if (value <= 0) {
            throw new IllegalArgumentException("app.chat-history.memory." + property + " must be greater than zero");
        }
    }

    private static void requireAtLeast(int value, int minimum, String property) {
        if (value < minimum) {
            throw new IllegalArgumentException(
                    "app.chat-history.memory." + property + " must be at least " + minimum);
        }
    }

    private static void requireAtMost(int value, int maximum, String property) {
        if (value > maximum) {
            throw new IllegalArgumentException(
                    "app.chat-history.memory." + property + " must not exceed " + maximum);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getDigestMaxChars() {
        return digestMaxChars;
    }

    public void setDigestMaxChars(int digestMaxChars) {
        this.digestMaxChars = digestMaxChars;
    }

    public int getFoldThresholdMessages() {
        return foldThresholdMessages;
    }

    public void setFoldThresholdMessages(int foldThresholdMessages) {
        this.foldThresholdMessages = foldThresholdMessages;
    }

    public int getRecallCandidates() {
        return recallCandidates;
    }

    public void setRecallCandidates(int recallCandidates) {
        this.recallCandidates = recallCandidates;
    }

    public int getRecallMaxEpisodes() {
        return recallMaxEpisodes;
    }

    public void setRecallMaxEpisodes(int recallMaxEpisodes) {
        this.recallMaxEpisodes = recallMaxEpisodes;
    }

    public int getRecallSnippetChars() {
        return recallSnippetChars;
    }

    public void setRecallSnippetChars(int recallSnippetChars) {
        this.recallSnippetChars = recallSnippetChars;
    }
}
