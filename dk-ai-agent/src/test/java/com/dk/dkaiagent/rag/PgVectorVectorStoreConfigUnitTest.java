package com.dk.dkaiagent.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PgVectorVectorStoreConfigUnitTest {

    @Test
    void knowledgeBaseVersionIgnoresDocumentOrderAndRandomIds() {
        List<Document> firstLoad = List.of(
                document("random-a", "案例甲", "心理篇.md", "心理"),
                document("random-b", "案例乙", "职场篇.md", "职场")
        );
        List<Document> secondLoad = List.of(
                document("another-b", "案例乙", "职场篇.md", "职场"),
                document("another-a", "案例甲", "心理篇.md", "心理")
        );

        assertEquals(
                PgVectorVectorStoreConfig.calculateKnowledgeBaseVersion(firstLoad),
                PgVectorVectorStoreConfig.calculateKnowledgeBaseVersion(secondLoad)
        );
    }

    @Test
    void knowledgeBaseVersionChangesWhenContentChanges() {
        List<Document> before = List.of(document("a", "原始案例", "心理篇.md", "心理"));
        List<Document> after = List.of(document("b", "更新后的案例", "心理篇.md", "心理"));

        assertNotEquals(
                PgVectorVectorStoreConfig.calculateKnowledgeBaseVersion(before),
                PgVectorVectorStoreConfig.calculateKnowledgeBaseVersion(after)
        );
    }

    @Test
    void knowledgeBaseVersionChangesWhenEmbeddingChanges() {
        List<Document> documents = List.of(document("a", "同一份案例", "心理篇.md", "心理"));

        assertNotEquals(
                PgVectorVectorStoreConfig.calculateKnowledgeBaseVersion(documents, "embedding-a"),
                PgVectorVectorStoreConfig.calculateKnowledgeBaseVersion(documents, "embedding-b")
        );
    }

    @Test
    void knowledgeBaseFilterExpressionIsValid() {
        SearchRequest request = SearchRequest.builder()
                .query("test")
                .filterExpression("knowledgeBase == '" +
                        PgVectorVectorStoreConfig.KNOWLEDGE_BASE_NAME + "'")
                .build();

        assertNotNull(request.getFilterExpression());
    }

    @Test
    void vectorStoreBeanDoesNotQueryBeforeSpringInitializesItsSchema() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);

        VectorStore vectorStore = new PgVectorVectorStoreConfig()
                .pgVectorVectorStore(jdbcTemplate, embeddingModel);

        assertNotNull(vectorStore);
        verifyNoInteractions(jdbcTemplate);
    }

    private Document document(String id, String text, String filename, String status) {
        return new Document(id, text, Map.of("filename", filename, "status", status));
    }
}
