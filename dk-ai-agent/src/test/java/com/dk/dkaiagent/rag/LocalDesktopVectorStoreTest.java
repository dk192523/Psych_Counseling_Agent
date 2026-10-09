package com.dk.dkaiagent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LocalDesktopVectorStoreTest {
    @TempDir Path data;

    record Fixture(JdbcTemplate jdbc, EmbeddingModel model, LocalDesktopVectorStore store) {}
    Fixture open(String version) {
        var source = new DriverManagerDataSource("jdbc:h2:file:" + data.resolve("vectors").toString().replace('\\', '/')
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        var jdbc = new JdbcTemplate(source);
        var model = mock(EmbeddingModel.class);
        when(model.embed(anyString())).thenReturn(new float[]{1, 0, 0});
        when(model.embed(any(Document.class))).thenAnswer(call -> {
            String text = ((Document) call.getArgument(0)).getText();
            return switch (text) {
                case "orthogonal" -> new float[]{0, 1, 0};
                case "angled" -> new float[]{1, 1, 0};
                default -> new float[]{1, 0, 0};
            };
        });
        return new Fixture(jdbc, model, new LocalDesktopVectorStore(jdbc, model, new ObjectMapper(),
                new DataSourceTransactionManager(source), 3, version));
    }
    Document doc(String id, String text, String domain) {
        return Document.builder().id(id).text(text).metadata(Map.of("knowledgeBase", "psych-counseling", "knowledgeDomain", domain)).build();
    }
    SearchRequest search(String filter, int topK, double threshold) {
        return SearchRequest.builder().query("query").topK(topK).similarityThreshold(threshold).filterExpression(filter).build();
    }

    @Test void survivesDatabaseCloseAndPreservesExactCosineScoresAndDomainFilter() {
        var first = open("v1");
        first.store().add(List.of(doc("a", "aligned", "psych-cases"), doc("b", "angled", "psych-cases"), doc("c", "orthogonal", "psych-cases"), doc("d", "aligned", "psych-exams")));
        first.jdbc().execute("SHUTDOWN");
        var reopened = open("v1");
        try {
            var hits = reopened.store().similaritySearch(search("knowledgeBase == 'psych-counseling' && (knowledgeDomain == 'psych-cases')", 2, 0.5));
            assertEquals(List.of("a", "b"), hits.stream().map(Document::getId).toList());
            assertEquals(1, hits.getFirst().getScore(), 1e-9);
            assertEquals(1 / Math.sqrt(2), hits.get(1).getScore(), 1e-7);
            verify(reopened.model(), never()).embed(any(Document.class));
            reopened.store().delete(List.of("a"));
            assertEquals("b", reopened.store().similaritySearch(search("knowledgeDomain == 'psych-cases'", 1, 0)).getFirst().getId());
        } finally { reopened.jdbc().execute("SHUTDOWN"); }
    }

    @Test void embeddingVersionAndDimensionsAreNotMixed() {
        var fixture = open("v1");
        fixture.store().add(List.of(doc("a", "aligned", "psych-cases")));
        fixture.jdbc().execute("SHUTDOWN");
        var changed = open("v2");
        try {
            assertTrue(changed.store().similaritySearch(search("knowledgeDomain == 'psych-cases'", 4, 0)).isEmpty());
            when(changed.model().embed(any(Document.class))).thenReturn(new float[]{1, 0});
            assertThrows(IllegalArgumentException.class, () -> changed.store().add(List.of(doc("b", "bad", "psych-cases"))));
            assertEquals(1, changed.jdbc().queryForObject("SELECT count(*) FROM local_desktop_vector_store", Integer.class));
        } finally { changed.jdbc().execute("SHUTDOWN"); }
    }

    @Test void failedEmbeddingPreservesOldSnapshotAndSuccessfulReplacementKeepsUnrelatedDomains() {
        var fixture = open("v1");
        try {
            var old = LocalDesktopVectorStoreConfig.snapshot(List.of(doc("source", "old", "psych-cases")), "v1");
            fixture.store().synchronizeKnowledgeBase(old);
            fixture.store().add(List.of(doc("exam", "aligned", "psych-exams")));
            var replacement = LocalDesktopVectorStoreConfig.snapshot(List.of(doc("source", "new", "psych-cases")), "v1");
            doThrow(new IllegalStateException("model unavailable")).when(fixture.model()).embed(argThat((Document document) -> "new".equals(document.getText())));
            assertThrows(IllegalStateException.class, () -> fixture.store().synchronizeKnowledgeBase(replacement));
            assertEquals("old", fixture.store().similaritySearch(search("knowledgeDomain == 'psych-cases'", 1, 0)).getFirst().getText());
            doReturn(new float[]{1, 0, 0}).when(fixture.model()).embed(any(Document.class));
            fixture.store().synchronizeKnowledgeBase(replacement);
            assertEquals("new", fixture.store().similaritySearch(search("knowledgeBaseVersion == '" + replacement.getFirst().getMetadata().get("knowledgeBaseVersion") + "'", 1, 0)).getFirst().getText());
            assertEquals("exam", fixture.store().similaritySearch(search("knowledgeDomain == 'psych-exams'", 1, 0)).getFirst().getId());
            assertEquals(3, fixture.jdbc().queryForObject("SELECT count(*) FROM local_desktop_vector_store", Integer.class));
        } finally { fixture.jdbc().execute("SHUTDOWN"); }
    }

    @Test void sameIdAndCountCannotReuseTamperedContentOrMetadata() {
        var fixture = open("v1");
        try {
            var snapshot = LocalDesktopVectorStoreConfig.snapshot(List.of(doc("source", "approved", "psych-cases")), "v1");
            fixture.store().synchronizeKnowledgeBase(snapshot);
            fixture.jdbc().update("UPDATE local_desktop_vector_store SET content = ? WHERE id = ?",
                    "unapproved replacement", snapshot.getFirst().getId());
            clearInvocations(fixture.model());
            fixture.store().synchronizeKnowledgeBase(snapshot);
            verify(fixture.model()).embed(any(Document.class));
            assertEquals("approved", fixture.store().similaritySearch(search("knowledgeBaseVersion == '"
                    + snapshot.getFirst().getMetadata().get("knowledgeBaseVersion") + "'", 1, 0)).getFirst().getText());
            var tampered = new java.util.HashMap<>(snapshot.getFirst().getMetadata());
            tampered.put("manifestSourceId", "unapproved-source");
            fixture.jdbc().update("UPDATE local_desktop_vector_store SET metadata = ? WHERE id = ?",
                    new ObjectMapper().writeValueAsString(tampered), snapshot.getFirst().getId());
            clearInvocations(fixture.model());
            fixture.store().synchronizeKnowledgeBase(snapshot);
            verify(fixture.model()).embed(any(Document.class));
        } catch (java.io.IOException failure) { throw new RuntimeException(failure); }
        finally { fixture.jdbc().execute("SHUTDOWN"); }
    }

    @Test void filteringHandlesGroupsMembershipAndSqlNullSemantics() {
        var fixture = open("v1");
        try {
            fixture.store().add(List.of(doc("a", "aligned", "psych-cases"), doc("b", "aligned", "psych-policy")));
            var hits = fixture.store().similaritySearch(search("knowledgeBase == 'psych-counseling' && (knowledgeDomain in ['psych-cases', 'psych-policy'])", 4, 0));
            assertEquals(2, hits.size());
            var notMissing = new Filter.Expression(Filter.ExpressionType.NOT, new Filter.Expression(Filter.ExpressionType.EQ, new Filter.Key("missing"), new Filter.Value("value")));
            assertFalse(LocalDesktopVectorStore.matches(notMissing, Map.of()));
            fixture.store().delete(new Filter.Expression(Filter.ExpressionType.EQ, new Filter.Key("knowledgeDomain"), new Filter.Value("psych-cases")));
            assertEquals(1, fixture.jdbc().queryForObject("SELECT count(*) FROM local_desktop_vector_store", Integer.class));
        } finally { fixture.jdbc().execute("SHUTDOWN"); }
    }
}
