package com.dk.dkaiagent.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeAdmissionStartupTest {
    @Test void pgProfileEmptyManifestStartsClosedWithoutReadingOrDeletingLegacyRows() {
        CounselingDocumentLoader loader = mock(CounselingDocumentLoader.class);
        when(loader.loadMarkdowns()).thenReturn(List.of());
        var policy = new KnowledgeAdmissionPolicy();
        var config = new PgVectorVectorStoreConfig();
        ReflectionTestUtils.setField(config, "counselingDocumentLoader", loader);
        ReflectionTestUtils.setField(config, "admissionPolicy", policy);
        VectorStore store = mock(VectorStore.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        assertDoesNotThrow(() -> config.pgVectorKnowledgeBaseInitializer(store, jdbc)
                .run(new DefaultApplicationArguments(new String[0])));
        assertTrue(policy.snapshot().isEmpty());
        verifyNoInteractions(store, jdbc);
    }

    @Test void localProfileEmptyManifestStartsClosedWithoutSynchronizingOrDeletingLegacyRows() {
        CounselingDocumentLoader loader = mock(CounselingDocumentLoader.class);
        when(loader.loadMarkdowns()).thenReturn(List.of());
        var policy = new KnowledgeAdmissionPolicy();
        LocalDesktopVectorStore store = mock(LocalDesktopVectorStore.class);
        assertDoesNotThrow(() -> new LocalDesktopVectorStoreConfig()
                .localDesktopKnowledgeBaseInitializer(store, loader, "embedding-v1", policy)
                .run(new DefaultApplicationArguments(new String[0])));
        assertTrue(policy.snapshot().isEmpty());
        verifyNoInteractions(store);
    }

    @Test void localSnapshotActivationFollowsSuccessfulPersistenceAndVerification() throws Exception {
        Document source = Document.builder().text("审核内容").metadata("knowledgeDomain", "psych-cases")
                .metadata("manifestSourceId", "case-source").build();
        CounselingDocumentLoader loader = mock(CounselingDocumentLoader.class);
        when(loader.loadMarkdowns()).thenReturn(List.of(source));
        KnowledgeAdmissionPolicy policy = new KnowledgeAdmissionPolicy();
        LocalDesktopVectorStore store = mock(LocalDesktopVectorStore.class);
        doAnswer(call -> {
            assertTrue(policy.snapshot().isEmpty(), "retrieval must remain closed during persistence");
            return null;
        }).when(store).synchronizeKnowledgeBase(anyList());
        var runner = new LocalDesktopVectorStoreConfig().localDesktopKnowledgeBaseInitializer(store, loader, "embedding-v1", policy);
        runner.run(new DefaultApplicationArguments(new String[0]));
        assertEquals(java.util.Set.of("psych-cases"), policy.snapshot().orElseThrow().availableDomains());
        doThrow(new IllegalStateException("verification failed")).when(store).synchronizeKnowledgeBase(anyList());
        assertThrows(IllegalStateException.class, () -> runner.run(new DefaultApplicationArguments(new String[0])));
        assertTrue(policy.snapshot().isEmpty());
    }
}
