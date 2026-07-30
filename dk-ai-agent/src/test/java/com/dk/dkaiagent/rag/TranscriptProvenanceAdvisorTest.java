package com.dk.dkaiagent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscriptProvenanceAdvisorTest {

    private static final String SLUG = "2026-07-18-call-07";

    @TempDir
    Path transcriptDirectory;

    @Test
    void appendsTimestampedRawExcerptForRetrievedSummary() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        writeTranscript(objectMapper);
        TranscriptProvenanceAdvisor advisor = new TranscriptProvenanceAdvisor(
                new TranscriptSearchService(objectMapper, transcriptDirectory)
        );
        Document summary = new Document(
                "视频：https://example.com/video（案例编号 " + SLUG + "）\n父亲担心女儿洁癖和减肥。",
                Map.of("title", "父亲求助女儿洁癖减肥")
        );
        String firstStagePrompt = "父亲要不要介入女儿的洁癖减肥？\n\n首层摘要：父亲担心女儿洁癖和减肥。";
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(firstStagePrompt))
                .context(Map.of(
                        TranscriptProvenanceAdvisor.ORIGINAL_QUERY, "父亲要不要介入女儿的洁癖减肥？",
                        QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS, List.of(summary)
                ))
                .build();

        ChatClientRequest enriched = advisor.before(request, null);

        String promptText = enriched.prompt().getUserMessage().getText();
        assertTrue(promptText.contains("父亲要不要介入女儿的洁癖减肥？"));
        assertTrue(promptText.contains("首层摘要：父亲担心女儿洁癖和减肥。"));
        assertTrue(promptText.contains("逐字稿二级检索片段"));
        assertTrue(promptText.contains(SLUG));
        assertTrue(promptText.contains("00:00:01-00:00:08"));
        assertTrue(promptText.contains("https://example.com/video?t=1"));
        assertTrue(enriched.context().containsKey(TranscriptProvenanceAdvisor.RETRIEVED_SOURCES));
    }

    @Test
    void leavesRequestUntouchedWhenRawTranscriptIsMissing() {
        TranscriptProvenanceAdvisor advisor = new TranscriptProvenanceAdvisor(
                new TranscriptSearchService(new ObjectMapper(), transcriptDirectory)
        );
        Document summary = new Document(
                "视频：https://example.com/video（案例编号 " + SLUG + "）",
                Map.of("title", "缺失逐字稿案例")
        );
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt("查询缺失案例"))
                .context(Map.of(
                        TranscriptProvenanceAdvisor.ORIGINAL_QUERY, "查询缺失案例",
                        QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS, List.of(summary)
                ))
                .build();

        assertSame(request, advisor.before(request, null));
        assertFalse(request.context().containsKey(TranscriptProvenanceAdvisor.RETRIEVED_SOURCES));
    }

    private void writeTranscript(ObjectMapper objectMapper) throws IOException {
        Map<String, Object> transcript = new LinkedHashMap<>();
        transcript.put("slug", SLUG);
        transcript.put("title", "父亲求助女儿洁癖减肥");
        transcript.put("url", "https://example.com/video");
        transcript.put("cues", List.of(
                Map.of("number", 1, "start", "00:00:01", "end", "00:00:03", "text", "女儿有洁癖反复洗手"),
                Map.of("number", 2, "start", "00:00:04", "end", "00:00:05", "text", "体重很轻还执着减肥"),
                Map.of("number", 3, "start", "00:00:06", "end", "00:00:08", "text", "父亲问到底要不要介入")
        ));
        objectMapper.writeValue(transcriptDirectory.resolve(SLUG + ".json").toFile(), transcript);
    }
}
