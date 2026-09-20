package com.dk.dkaiagent.history;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RetentionPropertiesTest {

    @Test
    void defaultsKeepDeletionDisabledAndRetentionUnconfigured() {
        RetentionProperties properties = new RetentionProperties();

        assertFalse(properties.isApply());
        assertEquals(0, properties.getConversationDays());
        assertEquals(100, properties.getBatchSize());
        assertDoesNotThrow(properties::validate);
    }

    @Test
    void applyRejectsMissingNegativeAndExcessiveRetentionDays() {
        for (int days : new int[]{0, -1, 36_501}) {
            RetentionProperties properties = new RetentionProperties();
            properties.setApply(true);
            properties.setConversationDays(days);

            assertThrows(IllegalArgumentException.class, properties::validate,
                    "conversationDays=" + days);
        }
    }

    @Test
    void applyRejectsInvalidBatchSizes() {
        for (int batchSize : new int[]{-1, 0, 501}) {
            RetentionProperties properties = new RetentionProperties();
            properties.setApply(true);
            properties.setConversationDays(1);
            properties.setBatchSize(batchSize);

            assertThrows(IllegalArgumentException.class, properties::validate,
                    "batchSize=" + batchSize);
        }
    }

    @Test
    void applyAcceptsInclusiveRetentionAndBatchBoundaries() {
        for (int days : new int[]{1, 36_500}) {
            for (int batchSize : new int[]{1, 500}) {
                RetentionProperties properties = new RetentionProperties();
                properties.setApply(true);
                properties.setConversationDays(days);
                properties.setBatchSize(batchSize);

                assertDoesNotThrow(properties::validate,
                        "conversationDays=" + days + ", batchSize=" + batchSize);
            }
        }
    }
}
