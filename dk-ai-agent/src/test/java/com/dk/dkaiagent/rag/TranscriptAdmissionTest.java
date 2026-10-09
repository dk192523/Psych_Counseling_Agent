package com.dk.dkaiagent.rag;

import com.dk.dkaiagent.tools.TranscriptLookupTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TranscriptAdmissionTest {
    @Test void transcriptCompatibilityToolIsNeitherABeanNorAnLlmToolAndCannotReadRawText() throws Exception {
        TranscriptSearchService service = mock(TranscriptSearchService.class);
        TranscriptLookupTool tool = new TranscriptLookupTool(service);
        assertFalse(TranscriptLookupTool.class.isAnnotationPresent(Component.class));
        assertFalse(TranscriptLookupTool.class.getMethod("lookupTranscript", String.class, String.class).isAnnotationPresent(Tool.class));
        assertTrue(tool.lookupTranscript("2026-07-18-call-07", "请提供原话").contains("尚未获得生产知识库准入"));
        verifyNoInteractions(service);
    }
}
