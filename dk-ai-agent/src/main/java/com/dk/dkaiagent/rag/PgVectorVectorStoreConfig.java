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
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType.COSINE_DISTANCE;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.HNSW;

@Configuration
@Slf4j
public class PgVectorVectorStoreConfig {

    public static final String KNOWLEDGE_BASE_NAME = "psych-counseling";
    public static final Set<String> PRODUCTION_DOMAINS = Set.of(
            KnowledgeDomain.CASES.getValue(), KnowledgeDomain.KNOWLEDGE.getValue(), KnowledgeDomain.POLICY.getValue());
    public static final String PRODUCTION_FILTER = KnowledgeIntentRouter.filterExpression(PRODUCTION_DOMAINS);
    private static final List<String> VERSION_METADATA = List.of(
            "filename", "status", "knowledgeDomain", "sourceId", "documentId", "chunkId",
            "sourceVersion", "contentHash", "citationUrl");

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
        List<Document> sourceDocuments = counselingDocumentLoader.loadMarkdowns().stream()
                .filter(document -> PRODUCTION_DOMAINS.contains(document.getMetadata()
                        .getOrDefault("knowledgeDomain", KnowledgeDomain.CASES.getValue())))
                .map(document -> document.mutate().metadata("knowledgeDomain", document.getMetadata()
                        .getOrDefault("knowledgeDomain", KnowledgeDomain.CASES.getValue())).build())
                .sorted(Comparator.comparing(PgVectorVectorStoreConfig::documentSortKey).thenComparing(Document::getText))
                .toList();
        if (sourceDocuments.isEmpty()) {
            throw new IllegalStateException("未加载到心理咨询知识库文档，拒绝使用旧向量数据启动");
        }

        String knowledgeBaseVersion = calculateKnowledgeBaseVersion(sourceDocuments, embeddingVersion);
        List<Document> documents = new ArrayList<>();
        for (int index = 0; index < sourceDocuments.size(); index++) {
            Document document = sourceDocuments.get(index);
            // Include the snapshot version so an upsert cannot overwrite the last usable snapshot.
            String identity = KNOWLEDGE_BASE_NAME + "\u0000" + knowledgeBaseVersion + "\u0000"
                    + index + "\u0000" + documentSortKey(document);
            documents.add(document.mutate()
                        .id(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString())
                        .metadata("knowledgeBase", KNOWLEDGE_BASE_NAME)
                        .metadata("knowledgeBaseVersion", knowledgeBaseVersion)
                        .build());
        }

        if (!snapshotCountsMatch(jdbcTemplate, documents, knowledgeBaseVersion)) {
            log.info("灌注新的心理咨询知识库快照：version={}, documents={}", knowledgeBaseVersion, documents.size());
            vectorStore.add(documents);
            if (!snapshotCountsMatch(jdbcTemplate, documents, knowledgeBaseVersion)) {
                throw new IllegalStateException("新知识库快照数量校验失败，保留旧快照，拒绝启动");
            }
        }
        // Cleanup runs only after the complete replacement is persisted and verified.
        for (String domain : PRODUCTION_DOMAINS) {
            jdbcTemplate.update("DELETE FROM public.vector_store WHERE metadata->>'knowledgeBase' = ? "
                            + "AND metadata->>'knowledgeDomain' = ? "
                            + "AND metadata->>'knowledgeBaseVersion' IS DISTINCT FROM ?",
                    KNOWLEDGE_BASE_NAME, domain, knowledgeBaseVersion);
        }
        // Match known legacy source filenames exactly; unrelated applications and other domains survive.
        sourceDocuments.stream().filter(document -> KnowledgeDomain.CASES.getValue()
                        .equals(document.getMetadata().get("knowledgeDomain")))
                .map(document -> document.getMetadata().get("filename"))
                .filter(java.util.Objects::nonNull).distinct().forEach(filename -> jdbcTemplate.update(
                        "DELETE FROM public.vector_store WHERE metadata->>'knowledgeDomain' IS NULL "
                                + "AND metadata->>'filename' = ? "
                                + "AND (metadata->>'knowledgeBase' = ? OR metadata->>'knowledgeBase' IS NULL)",
                        filename, KNOWLEDGE_BASE_NAME));
        log.info("心理咨询知识库快照已就绪：version={}, documents={}", knowledgeBaseVersion, documents.size());
    }

    private boolean snapshotCountsMatch(JdbcTemplate jdbcTemplate, List<Document> documents, String version) {
        for (String domain : PRODUCTION_DOMAINS) {
            long expected = documents.stream().filter(document -> domain.equals(
                    document.getMetadata().get("knowledgeDomain"))).count();
            Long actual = jdbcTemplate.queryForObject("SELECT count(*) FROM public.vector_store "
                            + "WHERE metadata->>'knowledgeBase' = ? AND metadata->>'knowledgeBaseVersion' = ? "
                            + "AND metadata->>'knowledgeDomain' = ?",
                    Long.class, KNOWLEDGE_BASE_NAME, version, domain);
            if (!Long.valueOf(expected).equals(actual)) {
                return false;
            }
        }
        return true;
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
        StringBuilder key = new StringBuilder();
        for (String field : VERSION_METADATA) {
            String value = String.valueOf(document.getMetadata().getOrDefault(field, ""));
            key.append(value.length()).append(':').append(value);
        }
        return key.toString();
    }
}
