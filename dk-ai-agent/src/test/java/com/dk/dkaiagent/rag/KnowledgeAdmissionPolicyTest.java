package com.dk.dkaiagent.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class KnowledgeAdmissionPolicyTest {
    static Document approved(String domain) {
        return LocalDesktopVectorStoreConfig.snapshot(List.of(Document.builder().text("已审核资料")
                .metadata(Map.of("knowledgeDomain", domain, "manifestSourceId", "approved-source"))
                .build()), "test-embedding").getFirst();
    }

    @Test void emptyAdmissionNeverTouchesVectorStoreEvenIfLegacyRowsExist() {
        KnowledgeAdmissionPolicy policy = new KnowledgeAdmissionPolicy();
        VectorStore store = mock(VectorStore.class);
        assertTrue(policy.search(store, SearchRequest.builder().query("我的困扰").build(),
                KnowledgeIntentRouter.route("我的困扰")).isEmpty());
        assertTrue(policy.retrievalView(store, KnowledgeIntentRouter.route("CBT 是什么"))
                .similaritySearch(SearchRequest.builder().query("重写查询").build()).isEmpty());
        verifyNoInteractions(store);
        assertTrue(policy.snapshot().isEmpty());
    }

    @Test void activeFilterPinsExactVersionAndOriginalIntentAndRejectsLegacyAndForgedHits() {
        KnowledgeAdmissionPolicy policy = new KnowledgeAdmissionPolicy();
        Document approved = approved("psych-cases");
        policy.activateVerified(List.of(approved));
        VectorStore store = mock(VectorStore.class);
        Document legacy = new Document("legacy", "旧案例", Map.of("knowledgeDomain", "psych-cases"));
        Document forged = approved.mutate().text("同 ID 的未审核原文").build();
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(legacy, forged, approved));
        var route = KnowledgeIntentRouter.route("我和室友吵架了");
        assertEquals(List.of(approved), policy.search(store, SearchRequest.builder().query("考试题重写").build(), route));
        ArgumentCaptor<SearchRequest> request = ArgumentCaptor.forClass(SearchRequest.class);
        verify(store).similaritySearch(request.capture());
        String filter = request.getValue().getFilterExpression().toString();
        assertTrue(filter.contains("knowledgeBaseVersion"));
        assertTrue(filter.contains((String) approved.getMetadata().get("knowledgeBaseVersion")));
        assertFalse(filter.contains("psych-exams"));
        assertFalse(filter.contains("psych-policy"));
    }

    @Test void domainAdmissionDoesNotLetCasesAnswerProfessionalPolicyOrExamQuestions() {
        KnowledgeAdmissionPolicy policy = new KnowledgeAdmissionPolicy();
        policy.activateVerified(List.of(approved("psych-cases")));
        for (String query : List.of("CBT 是什么", "咨询保密的法律边界是什么", "给我心理学考试题库")) {
            assertEquals(KnowledgeAdmissionPolicy.INSUFFICIENT_MESSAGE, policy.insufficientAnswer(query).orElseThrow());
        }
        policy.activateVerified(List.of(approved("psych-knowledge")));
        assertTrue(policy.insufficientAnswer("CBT 是什么").isEmpty());
        assertTrue(policy.insufficientAnswer("咨询保密的法律边界是什么").isPresent());
        policy.activateVerified(List.of(approved("psych-policy")));
        assertTrue(policy.insufficientAnswer("咨询保密的法律边界是什么").isEmpty());
        assertTrue(policy.insufficientAnswer("CBT 是什么").isPresent());
    }

    @Test void ventingAndCrisisNeverReceiveKnowledgeInsufficiencyRefusal() {
        KnowledgeAdmissionPolicy policy = new KnowledgeAdmissionPolicy();
        for (String query : List.of("我很焦虑，只想倾诉", "我有焦虑症，只想说说", "我吞了一整瓶药", "我不想活了")) {
            assertTrue(policy.insufficientAnswer(query).isEmpty(), query);
        }
    }

    @Test void manifestIdentityAndAdmissionSchemaSeparateSnapshotsFromLegacyHash() {
        Document first = Document.builder().id("same-id").text("同样内容")
                .metadata(Map.of("manifestSourceId", "a")).build();
        Document second = Document.builder().id("same-id").text("同样内容")
                .metadata(Map.of("manifestSourceId", "b")).build();
        assertNotEquals(PgVectorVectorStoreConfig.calculateKnowledgeBaseVersion(List.of(first)),
                PgVectorVectorStoreConfig.calculateKnowledgeBaseVersion(List.of(second)));
        String version = PgVectorVectorStoreConfig.calculateKnowledgeBaseVersion(List.of(first));
        assertEquals(64, version.length());
        assertNotEquals(CounselingDocumentLoader.sha256("unknown-embedding\u0000"
                + PgVectorVectorStoreConfig.documentSortKey(first) + "\u0000同样内容\u0000"), version);
    }
}
