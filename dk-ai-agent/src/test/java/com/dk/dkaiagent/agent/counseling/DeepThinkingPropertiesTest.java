package com.dk.dkaiagent.agent.counseling;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeepThinkingPropertiesTest {

    @Test
    void acceptsDefaultsThatFitThePythonContract() {
        assertDoesNotThrow(() -> new DeepThinkingProperties().validate());
    }

    @Test
    void rejectsValuesThatPythonWouldReturnAsUnprocessableEntity() {
        DeepThinkingProperties properties = new DeepThinkingProperties();
        properties.setHistoryMessages(31);
        assertThrows(IllegalArgumentException.class, properties::validate);

        properties = new DeepThinkingProperties();
        properties.setMaxQueries(9);
        assertThrows(IllegalArgumentException.class, properties::validate);

        properties = new DeepThinkingProperties();
        properties.setAssociationHypotheses(6);
        assertThrows(IllegalArgumentException.class, properties::validate);

        properties = new DeepThinkingProperties();
        properties.setCandidateLimit(21);
        assertThrows(IllegalArgumentException.class, properties::validate);

        properties = new DeepThinkingProperties();
        properties.setEvidenceLimit(9);
        assertThrows(IllegalArgumentException.class, properties::validate);

        properties = new DeepThinkingProperties();
        properties.setTranscriptSnippetsPerCase(4);
        assertThrows(IllegalArgumentException.class, properties::validate);

        properties = new DeepThinkingProperties();
        properties.setCandidateTextChars(2_001);
        assertThrows(IllegalArgumentException.class, properties::validate);
    }
}
