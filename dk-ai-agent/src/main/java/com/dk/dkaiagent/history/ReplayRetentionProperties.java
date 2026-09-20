package com.dk.dkaiagent.history;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "app.chat-history")
public class ReplayRetentionProperties {
    private Duration replayRetention = Duration.ofDays(7);

    @PostConstruct
    public void validate() {
        if (replayRetention == null || replayRetention.compareTo(Duration.ofHours(1)) < 0
                || replayRetention.compareTo(Duration.ofDays(3_650)) > 0) {
            throw new IllegalArgumentException("app.chat-history.replay-retention must be between 1h and 3650d");
        }
    }

    public Duration getReplayRetention() { return replayRetention; }
    public void setReplayRetention(Duration replayRetention) { this.replayRetention = replayRetention; }
}
