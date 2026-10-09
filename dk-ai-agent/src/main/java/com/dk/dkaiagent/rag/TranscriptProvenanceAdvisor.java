package com.dk.dkaiagent.rag;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.stereotype.Component;

/** Compatibility advisor: production manifests do not admit raw transcript expansion. */
@Component
public class TranscriptProvenanceAdvisor implements BaseAdvisor {
    public static final String ORIGINAL_QUERY = "transcript_original_query";
    public static final String RETRIEVED_SOURCES = "transcript_retrieved_sources";

    public TranscriptProvenanceAdvisor(TranscriptSearchService ignored) { }
    @Override public int getOrder() { return 1; }
    @Override public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) { return request; }
    @Override public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) { return response; }
}
