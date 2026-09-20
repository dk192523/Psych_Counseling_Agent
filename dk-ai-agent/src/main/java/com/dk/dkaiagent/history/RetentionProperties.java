package com.dk.dkaiagent.history;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Whole-conversation retention is opt-in and independent of context and replay windows. */
@Component
@ConfigurationProperties(prefix = "app.data-retention")
public class RetentionProperties {
    private boolean apply = false;
    private int conversationDays = 0;
    private int batchSize = 100;

    @PostConstruct
    public void validate() {
        if (conversationDays < 0 || conversationDays > 36_500) {
            throw new IllegalArgumentException("app.data-retention.conversation-days must be between 0 and 36500");
        }
        if (apply && conversationDays == 0) {
            throw new IllegalArgumentException("app.data-retention.apply requires conversation-days of at least 1");
        }
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("app.data-retention.batch-size must be between 1 and 500");
        }
    }

    public boolean isApply() { return apply; }
    public void setApply(boolean apply) { this.apply = apply; }
    public int getConversationDays() { return conversationDays; }
    public void setConversationDays(int conversationDays) { this.conversationDays = conversationDays; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
}
