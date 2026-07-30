package com.dk.dkaiagent.rag;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_PGVECTOR_INTEGRATION_TESTS", matches = "true")
class PgVectorVectorStoreConfigTest {

    @Resource
    private VectorStore pgVectorVectorStore;

    @Test
    void pgVectorVectorStore() {
        String testRun = "pgvector-test-" + UUID.randomUUID();
        List<Document> documents = List.of(
                new Document(testRun + "-1", "最近找实习压力很大，总担心自己准备得不够。", Map.of("testRun", testRun)),
                new Document(testRun + "-2", "先把具体压力来源和身体反应说清楚。", Map.of("testRun", testRun)),
                new Document(testRun + "-3", "DK这小伙子比较帅气", Map.of("testRun", testRun)));
        try {
            pgVectorVectorStore.add(documents);
            List<Document> results = pgVectorVectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query("怎么学编程啊")
                            .topK(3)
                            .filterExpression("testRun == '" + testRun + "'")
                            .build()
            );
            Assertions.assertFalse(results.isEmpty());
        } finally {
            pgVectorVectorStore.delete(documents.stream().map(Document::getId).toList());
        }
    }
}
