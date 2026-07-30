package com.dk.dkaiagent.rag;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType.COSINE_DISTANCE;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.HNSW;

@Configuration
@Slf4j
public class PgVectorVectorStoreConfig {

    public static final String KNOWLEDGE_BASE_NAME = "psych-counseling";

    @Resource
    private CounselingDocumentLoader counselingDocumentLoader;

    @Value("${app.rag.embedding-dimensions:384}")
    private int embeddingDimensions = 384;

    @Value("${app.rag.embedding-version:transformers-all-MiniLM-L6-v2-384-v1}")
    private String embeddingVersion = "transformers-all-MiniLM-L6-v2-384-v1";

    @Bean
    public VectorStore pgVectorVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(embeddingDimensions)
                .distanceType(COSINE_DISTANCE)       // Optional: defaults to COSINE_DISTANCE
                .indexType(HNSW)                     // Optional: defaults to HNSW
                .initializeSchema(true)              // Optional: defaults to false
                .schemaName("public")                // Optional: defaults to "public"
                .vectorTableName("vector_store")     // Optional: defaults to "vector_store"
                .maxDocumentBatchSize(10000)         // Optional: defaults to 10000
                .build();
    }

    @Bean
    public ApplicationRunner pgVectorKnowledgeBaseInitializer(
            @Qualifier("pgVectorVectorStore") VectorStore vectorStore,
            JdbcTemplate jdbcTemplate) {
        // ApplicationRunner 在 PgVectorStore.afterPropertiesSet() 建表后执行，避免全新数据库首次启动先查不存在的表。
        return args -> initializeKnowledgeBase(vectorStore, jdbcTemplate);
    }

    private void initializeKnowledgeBase(VectorStore vectorStore, JdbcTemplate jdbcTemplate) {
        List<Document> sourceDocuments = counselingDocumentLoader.loadMarkdowns();
        if (sourceDocuments.isEmpty()) {
            throw new IllegalStateException("未加载到心理咨询知识库文档，拒绝使用旧向量数据启动");
        }

        String knowledgeBaseVersion = calculateKnowledgeBaseVersion(sourceDocuments, embeddingVersion);
        List<Document> documents = sourceDocuments.stream()
                .map(document -> document.mutate()
                        .metadata("knowledgeBase", KNOWLEDGE_BASE_NAME)
                        .metadata("knowledgeBaseVersion", knowledgeBaseVersion)
                        .build())
                .toList();

        Long taggedKnowledgeBaseCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM public.vector_store WHERE metadata->>'knowledgeBase' = ?",
                Long.class,
                KNOWLEDGE_BASE_NAME
        );
        Long currentKnowledgeBaseCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM public.vector_store " +
                        "WHERE metadata->>'knowledgeBase' = ? AND metadata->>'knowledgeBaseVersion' = ?",
                Long.class,
                KNOWLEDGE_BASE_NAME,
                knowledgeBaseVersion
        );

        long expectedCount = documents.size();
        boolean knowledgeBaseIsCurrent = Long.valueOf(expectedCount).equals(taggedKnowledgeBaseCount)
                && Long.valueOf(expectedCount).equals(currentKnowledgeBaseCount);
        if (!knowledgeBaseIsCurrent) {
            log.info(
                    "检测到向量库版本不一致，重新灌注心理咨询知识库：version={}, expected={}, tagged={}, current={}",
                    knowledgeBaseVersion,
                    expectedCount,
                    taggedKnowledgeBaseCount,
                    currentKnowledgeBaseCount
            );
            jdbcTemplate.update(
                    "DELETE FROM public.vector_store WHERE metadata->>'knowledgeBase' = ?",
                    KNOWLEDGE_BASE_NAME
            );
            // 兼容清理升级前未打标签的旧来源摘要向量，保留同表中的其他应用数据。
            jdbcTemplate.update(
                    "DELETE FROM public.vector_store " +
                            "WHERE metadata->>'knowledgeBase' IS NULL AND metadata->>'filename' LIKE '咨询案例%'"
            );
            vectorStore.add(documents);
        } else {
            log.info("心理咨询知识库已是当前版本，跳过重复灌注：version={}, documents={}", knowledgeBaseVersion, expectedCount);
        }
    }

    static String calculateKnowledgeBaseVersion(List<Document> documents) {
        return calculateKnowledgeBaseVersion(documents, "unknown-embedding");
    }

    static String calculateKnowledgeBaseVersion(List<Document> documents, String embeddingVersion) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(embeddingVersion.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            documents.stream()
                    .sorted(Comparator
                            .comparing(PgVectorVectorStoreConfig::documentSortKey)
                            .thenComparing(Document::getText))
                    .forEach(document -> {
                        digest.update(documentSortKey(document).getBytes(StandardCharsets.UTF_8));
                        digest.update((byte) 0);
                        digest.update(document.getText().getBytes(StandardCharsets.UTF_8));
                        digest.update((byte) 0);
                    });
            return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }

    private static String documentSortKey(Document document) {
        return String.valueOf(document.getMetadata().getOrDefault("filename", ""))
                + "\u0000"
                + String.valueOf(document.getMetadata().getOrDefault("status", ""));
    }
}
