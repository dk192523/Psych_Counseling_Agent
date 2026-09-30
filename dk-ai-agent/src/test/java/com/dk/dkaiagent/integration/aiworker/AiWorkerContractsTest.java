package com.dk.dkaiagent.integration.aiworker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiWorkerContractsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void candidateMetadataRoundTripsWithoutChangingVersion() throws Exception {
        var candidate = new AiWorkerContracts.Candidate("C1", "", "睡眠知识", "正文", 0.8,
                "psych-knowledge", "guide.md", "chunk-1", "https://example.test/guide");
        String json = mapper.writeValueAsString(candidate);
        assertEquals(candidate, mapper.readValue(json, AiWorkerContracts.Candidate.class));
        assertEquals("guide.md", mapper.readTree(json).get("sourceId").asText());
        assertEquals("1", AiWorkerContracts.VERSION);
    }

    @Test
    void legacyJsonAndFiveArgumentConstructorHaveTheSameSafeDefaults() throws Exception {
        var candidate = mapper.readValue("""
                {"id":"C1","slug":"2026-03-08-call-01","title":"案例","text":"正文","vectorScore":0.8}
                """, AiWorkerContracts.Candidate.class);
        assertEquals(new AiWorkerContracts.Candidate(
                "C1", "2026-03-08-call-01", "案例", "正文", 0.8), candidate);
        assertEquals("psych-cases", candidate.domain());
        assertEquals("", candidate.sourceId());
        assertEquals("", candidate.chunkId());
        assertEquals("", candidate.citationUrl());
    }
}
