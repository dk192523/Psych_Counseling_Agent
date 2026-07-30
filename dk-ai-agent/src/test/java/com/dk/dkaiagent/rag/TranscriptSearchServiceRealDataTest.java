package com.dk.dkaiagent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TranscriptSearchServiceRealDataTest {

    @Test
    void findsTimestampedExcerptInArchivedTranscript() {
        String slug = "2026-07-18-call-07";
        Path transcriptDirectory = Path.of("../counseling-kb/raw").toAbsolutePath().normalize();
        assumeTrue(Files.isRegularFile(transcriptDirectory.resolve(slug + ".json")));

        TranscriptSearchService service = new TranscriptSearchService(new ObjectMapper(), transcriptDirectory);
        TranscriptSearchService.TranscriptSource source = service
                .search(slug, "女儿洁癖，体重八十斤还坚持减肥，父亲要不要介入", 3)
                .orElseThrow();

        assertFalse(source.snippets().isEmpty());
        assertTrue(source.snippets().stream().anyMatch(snippet ->
                snippet.text().contains("洁癖") || snippet.text().contains("减肥")));
        assertTrue(source.snippets().stream().allMatch(snippet -> !snippet.start().isBlank()));
    }
}
