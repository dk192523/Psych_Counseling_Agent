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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class TranscriptProvenanceAdvisorTest {

    private static final String SLUG = "2026-07-18-call-07";

    @TempDir
    Path transcriptDirectory;

    @Test
    void admittedSummaryNeverExpandsIntoUnapprovedRawTranscript() {
        TranscriptSearchService service = mock(TranscriptSearchService.class);
        TranscriptProvenanceAdvisor advisor = new TranscriptProvenanceAdvisor(service);
        Document summary = new Document("案例编号 " + SLUG, Map.of("knowledgeDomain", "psych-cases"));
        ChatClientRequest request = ChatClientRequest.builder().prompt(new Prompt("请提供原话"))
                .context(Map.of(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS, List.of(summary))).build();
        assertSame(request, advisor.before(request, null));
        verifyNoInteractions(service);
        assertFalse(request.context().containsKey(TranscriptProvenanceAdvisor.RETRIEVED_SOURCES));
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

    @Test
    void skipsProfessionalAndPolicyDocumentsContainingCaseReferences() {
        TranscriptSearchService searchService = mock(TranscriptSearchService.class);
        TranscriptProvenanceAdvisor advisor = new TranscriptProvenanceAdvisor(searchService);
        for (String domain : List.of("psych-knowledge", "psych-policy")) {
            Document document = new Document(
                    "引用案例编号 " + SLUG + " 的专业或政策说明。",
                    Map.of("knowledgeDomain", domain)
            );
            ChatClientRequest request = ChatClientRequest.builder()
                    .prompt(new Prompt("如何理解这项说明？"))
                    .context(Map.of(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS, List.of(document)))
                    .build();

            assertSame(request, advisor.before(request, null));
        }
        verifyNoInteractions(searchService);
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
