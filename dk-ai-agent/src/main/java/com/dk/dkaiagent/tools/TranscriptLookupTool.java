package com.dk.dkaiagent.tools;

import com.dk.dkaiagent.rag.TranscriptSearchService;

/** Retained as a compatibility surface, never registered as an LLM tool or a Spring bean. */
public class TranscriptLookupTool {
    public TranscriptLookupTool(TranscriptSearchService ignored) { }

    public String lookupTranscript(String slug, String query) {
        return "逐字稿尚未获得生产知识库准入，无法提供原文。";
    }
}
