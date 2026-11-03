package com.dk.dkaiagent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Configuration
@Profile("desktop")
public class DesktopVectorStoreConfig {
    // Keep the qualifier used by the counseling pipeline; profile selection supplies its implementation.
    @Bean("pgVectorVectorStore")
    public DesktopVectorStore desktopVectorStore(JdbcTemplate jdbc, EmbeddingModel model, ObjectMapper mapper,
            PlatformTransactionManager manager, @Value("${app.rag.embedding-dimensions:384}") int dimensions,
            @Value("${app.rag.embedding-version}") String embeddingVersion) {
        return new DesktopVectorStore(jdbc, model, mapper, manager, dimensions, embeddingVersion);
    }

    @Bean
    public ApplicationRunner desktopKnowledgeBaseInitializer(DesktopVectorStore store, CounselingDocumentLoader loader,
            @Value("${app.rag.embedding-version}") String embeddingVersion) {
        return args -> store.synchronizeKnowledgeBase(snapshot(loader.loadMarkdowns(), embeddingVersion));
    }

    static List<Document> snapshot(List<Document> source, String embeddingVersion) {
        List<Document> ordered = source.stream().filter(document -> PgVectorVectorStoreConfig.PRODUCTION_DOMAINS.contains(
                        document.getMetadata().getOrDefault("knowledgeDomain", KnowledgeDomain.CASES.getValue())))
                .map(document -> document.mutate().metadata("knowledgeDomain", document.getMetadata()
                        .getOrDefault("knowledgeDomain", KnowledgeDomain.CASES.getValue())).build())
                .sorted(Comparator.comparing(PgVectorVectorStoreConfig::documentSortKey).thenComparing(Document::getText)).toList();
        if (ordered.isEmpty()) throw new IllegalStateException("No counseling knowledge documents loaded");
        String version = PgVectorVectorStoreConfig.calculateKnowledgeBaseVersion(ordered, embeddingVersion);
        List<Document> documents = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index++) {
            Document document = ordered.get(index);
            String identity = PgVectorVectorStoreConfig.KNOWLEDGE_BASE_NAME + "\u0000" + version + "\u0000" + index
                    + "\u0000" + PgVectorVectorStoreConfig.documentSortKey(document);
            documents.add(document.mutate().id(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString())
                    .metadata("knowledgeBase", PgVectorVectorStoreConfig.KNOWLEDGE_BASE_NAME)
                    .metadata("knowledgeBaseVersion", version).build());
        }
        return List.copyOf(documents);
    }
}
