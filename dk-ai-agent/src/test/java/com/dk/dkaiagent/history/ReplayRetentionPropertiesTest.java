package com.dk.dkaiagent.history;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplayRetentionPropertiesTest {

    @Test
    void defaultsToSevenDays() {
        ReplayRetentionProperties properties = new ReplayRetentionProperties();

        assertEquals(Duration.ofDays(7), properties.getReplayRetention());
        assertDoesNotThrow(properties::validate);
    }

    @Test
    void rejectsNullAndDurationsOutsideInclusiveBounds() {
        Duration[] invalid = {
                null,
                Duration.ofSeconds(-1),
                Duration.ZERO,
                Duration.ofHours(1).minusNanos(1),
                Duration.ofDays(3_650).plusNanos(1)
        };
        for (Duration duration : invalid) {
            ReplayRetentionProperties properties = new ReplayRetentionProperties();
            properties.setReplayRetention(duration);

            assertThrows(IllegalArgumentException.class, properties::validate,
                    "replayRetention=" + duration);
        }
    }

    @Test
    void acceptsInclusiveBoundsAndAnInteriorDuration() {
        for (Duration duration : new Duration[]{
                Duration.ofHours(1), Duration.ofDays(30), Duration.ofDays(3_650)}) {
            ReplayRetentionProperties properties = new ReplayRetentionProperties();
            properties.setReplayRetention(duration);

            assertDoesNotThrow(properties::validate, "replayRetention=" + duration);
            assertEquals(duration, properties.getReplayRetention());
        }
    }
}
