package com.dk.dkaiagent.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/** A process starts closed. Only an entirely persisted and verified manifest snapshot opens retrieval. */
@Component
public final class KnowledgeAdmissionPolicy {
    public static final String ADMISSION_SCHEMA_VERSION = "manifest-production-v1";
    public static final String INSUFFICIENT_MESSAGE = "当前知识库还没有通过授权与审核的相关资料，暂时无法可靠回答这类专业、政策法律或考试题库问题。你可以提供已获授权的资料供核对；如果你想聊自己的感受或困扰，我仍然可以陪你梳理。";

    private final AtomicReference<Snapshot> active = new AtomicReference<>();

    public record Snapshot(String version, Set<String> availableDomains, Map<String, Document> documents) {
        public Snapshot {
            availableDomains = Set.copyOf(availableDomains);
            documents = Map.copyOf(documents);
        }
        public boolean accepts(Document document) {
            Document expected = documents.get(document.getId());
            return expected != null && sameDocument(expected, document);
        }
    }

    public Optional<Snapshot> snapshot() { return Optional.ofNullable(active.get()); }

    /** Called only after the store has verified every ID, content and metadata field. */
    void activateVerified(List<Document> documents) {
        if (documents.isEmpty()) { active.set(null); return; }
        String version = Objects.toString(documents.getFirst().getMetadata().get("knowledgeBaseVersion"), "");
        if (!version.matches("[a-f0-9]{64}") || documents.stream().anyMatch(document ->
                !version.equals(document.getMetadata().get("knowledgeBaseVersion"))
                || !PgVectorVectorStoreConfig.KNOWLEDGE_BASE_NAME.equals(document.getMetadata().get("knowledgeBase"))
                || !PgVectorVectorStoreConfig.PRODUCTION_DOMAINS.contains(document.getMetadata().get("knowledgeDomain"))
                || Objects.toString(document.getMetadata().get("manifestSourceId"), "").isBlank())) {
            throw new IllegalArgumentException("Invalid admitted knowledge snapshot");
        }
        Map<String, Document> byId = documents.stream().collect(Collectors.toUnmodifiableMap(Document::getId,
                document -> document.mutate().metadata(Map.copyOf(document.getMetadata())).build()));
        Set<String> domains = documents.stream().map(document -> (String) document.getMetadata().get("knowledgeDomain"))
                .collect(Collectors.toUnmodifiableSet());
        active.set(new Snapshot(version, domains, byId));
    }

    public Optional<String> insufficientAnswer(String message) {
        Set<String> required = KnowledgeIntentRouter.requiredDomains(message);
        Snapshot snapshot = active.get();
        return !required.isEmpty() && (snapshot == null || !snapshot.availableDomains().containsAll(required))
                ? Optional.of(INSUFFICIENT_MESSAGE) : Optional.empty();
    }

    public Optional<String> filterExpression(KnowledgeIntentRouter.Route route) {
        Snapshot snapshot = active.get();
        return filterExpression(snapshot, route);
    }

    private Optional<String> filterExpression(Snapshot snapshot, KnowledgeIntentRouter.Route route) {
        if (snapshot == null || !route.shouldRetrieve()) return Optional.empty();
        List<String> domains = route.allowedDomains().stream().filter(snapshot.availableDomains()::contains).toList();
        if (domains.isEmpty()) return Optional.empty();
        return Optional.of(KnowledgeIntentRouter.filterExpression(domains)
                + " && knowledgeBaseVersion == '" + snapshot.version() + "'");
    }

    public List<Document> search(VectorStore store, SearchRequest request, KnowledgeIntentRouter.Route originalRoute) {
        Snapshot snapshot = active.get();
        Optional<String> filter = filterExpression(snapshot, originalRoute);
        if (filter.isEmpty()) return List.of();
        SearchRequest admitted = SearchRequest.builder().query(request.getQuery()).topK(request.getTopK())
                .similarityThreshold(request.getSimilarityThreshold()).filterExpression(filter.get()).build();
        List<Document> hits = store.similaritySearch(admitted);
        if (hits == null) return List.of();
        return hits.stream().filter(snapshot::accepts)
                .filter(document -> originalRoute.allowedDomains().contains(document.getMetadata().get("knowledgeDomain")))
                .toList();
    }

    /** Advisor adapter keeps the original user route even when the advisor rewrites or augments the query. */
    public VectorStore retrievalView(VectorStore store, KnowledgeIntentRouter.Route route) {
        return new VectorStore() {
            @Override public List<Document> similaritySearch(SearchRequest request) { return search(store, request, route); }
            @Override public void add(List<Document> documents) { throw new UnsupportedOperationException("Read-only admission view"); }
            @Override public void delete(List<String> ids) { throw new UnsupportedOperationException("Read-only admission view"); }
            @Override public void delete(Filter.Expression filter) { throw new UnsupportedOperationException("Read-only admission view"); }
        };
    }

    static boolean sameDocument(Document expected, Document actual) {
        return expected.getId().equals(actual.getId()) && Objects.equals(expected.getText(), actual.getText())
                && expected.getMetadata().equals(actual.getMetadata());
    }
}
