package com.dk.dkaiagent.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;

/** Exact cosine retrieval, persisted in the same embedded database as conversations.
 * Search costs O(N * dimensions + N log(topK)), with O(topK * dimensions) live results.
 * Embeddings are prepared before writes; a failed model call never damages the durable snapshot.
 */
public final class DesktopVectorStore implements VectorStore {
    private static final TypeReference<Map<String, Object>> METADATA = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final EmbeddingModel model;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    private final int dimensions;
    private final String embeddingVersion;

    public DesktopVectorStore(JdbcTemplate jdbc, EmbeddingModel model, ObjectMapper mapper,
                              PlatformTransactionManager manager, int dimensions, String embeddingVersion) {
        if (dimensions < 1 || embeddingVersion == null || embeddingVersion.isBlank()) {
            throw new IllegalArgumentException("Embedding dimensions and version are required");
        }
        this.jdbc = jdbc;
        this.model = model;
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(manager);
        this.dimensions = dimensions;
        this.embeddingVersion = embeddingVersion;
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS desktop_vector_store (
                    id VARCHAR(128) PRIMARY KEY,
                    text CLOB NOT NULL,
                    metadata CLOB NOT NULL,
                    embedding VARBINARY NOT NULL,
                    dimensions INT NOT NULL,
                    embedding_version VARCHAR(256) NOT NULL
                )
                """);
    }

    @Override
    public void add(List<Document> documents) {
        List<Stored> prepared = prepare(documents);
        transaction.executeWithoutResult(status -> persistPreparedRows(prepared));
    }

    private List<Stored> prepare(List<Document> documents) {
        return documents.stream().map(document -> {
            if (!document.isText() || document.getText() == null) throw new IllegalArgumentException("Text documents required");
            float[] vector = model.embed(document);
            validate(vector);
            try {
                return new Stored(document.getId(), document.getText(), mapper.writeValueAsString(document.getMetadata()), encode(vector));
            } catch (Exception failure) {
                throw new IllegalArgumentException("Cannot serialize vector metadata", failure);
            }
        }).toList();
    }

    private void persistPreparedRows(List<Stored> prepared) {
        for (Stored row : prepared) {
            jdbc.update("""
                    MERGE INTO desktop_vector_store (id, text, metadata, embedding, dimensions, embedding_version)
                    KEY(id) VALUES (?, ?, ?, ?, ?, ?)
                    """, row.id(), row.text(), row.metadata(), row.embedding(), dimensions, embeddingVersion);
        }
    }

    /** Replace only this product's allowlisted domains, preserving unrelated application data. */
    void synchronizeKnowledgeBase(List<Document> documents) {
        if (documents.isEmpty()) throw new IllegalArgumentException("Knowledge snapshot must not be empty");
        String version = Objects.toString(documents.getFirst().getMetadata().get("knowledgeBaseVersion"), "");
        if (version.isBlank() || documents.stream().anyMatch(document ->
                !version.equals(document.getMetadata().get("knowledgeBaseVersion"))
                || !PgVectorVectorStoreConfig.KNOWLEDGE_BASE_NAME.equals(document.getMetadata().get("knowledgeBase"))
                || !PgVectorVectorStoreConfig.PRODUCTION_DOMAINS.contains(document.getMetadata().get("knowledgeDomain")))) {
            throw new IllegalArgumentException("Invalid knowledge snapshot metadata");
        }
        var current = allRows();
        var ids = documents.stream().map(Document::getId).collect(java.util.stream.Collectors.toSet());
        if (ids.size() != documents.size()) throw new IllegalArgumentException("Duplicate knowledge document id");
        long existing = current.stream().filter(row -> ids.contains(row.document().getId())
                && row.dimensions() == dimensions && embeddingVersion.equals(row.version())
                && version.equals(row.document().getMetadata().get("knowledgeBaseVersion"))).count();
        List<Stored> prepared = existing == documents.size() ? List.of() : prepare(documents);
        var filenames = documents.stream().filter(document -> KnowledgeDomain.CASES.getValue().equals(document.getMetadata().get("knowledgeDomain")))
                .map(document -> document.getMetadata().get("filename")).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        transaction.executeWithoutResult(status -> {
            persistPreparedRows(prepared);
            for (Row row : allRows()) {
                Map<String, Object> metadata = row.document().getMetadata();
                boolean owned = PgVectorVectorStoreConfig.KNOWLEDGE_BASE_NAME.equals(metadata.get("knowledgeBase"))
                        && PgVectorVectorStoreConfig.PRODUCTION_DOMAINS.contains(metadata.get("knowledgeDomain"));
                boolean legacy = metadata.get("knowledgeDomain") == null && filenames.contains(metadata.get("filename"))
                        && (metadata.get("knowledgeBase") == null || PgVectorVectorStoreConfig.KNOWLEDGE_BASE_NAME.equals(metadata.get("knowledgeBase")));
                if ((owned && !ids.contains(row.document().getId())) || legacy) {
                    jdbc.update("DELETE FROM desktop_vector_store WHERE id = ?", row.document().getId());
                }
            }
            Long persisted = jdbc.queryForObject("SELECT count(*) FROM desktop_vector_store WHERE dimensions = ? AND embedding_version = ?", Long.class, dimensions, embeddingVersion);
            if (persisted == null || persisted < documents.size()) throw new IllegalStateException("Incomplete knowledge snapshot");
        });
    }

    @Override
    public void delete(List<String> ids) {
        transaction.executeWithoutResult(status -> ids.forEach(id -> jdbc.update("DELETE FROM desktop_vector_store WHERE id = ?", id)));
    }

    @Override
    public void delete(Filter.Expression expression) {
        Objects.requireNonNull(expression, "Delete requires an explicit filter");
        transaction.executeWithoutResult(status -> {
            for (Row row : allRows()) if (matches(expression, row.document().getMetadata())) {
                jdbc.update("DELETE FROM desktop_vector_store WHERE id = ?", row.document().getId());
            }
        });
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        Objects.requireNonNull(request, "request");
        float[] query = model.embed(request.getQuery());
        validate(query);
        Comparator<Document> worstFirst = Comparator.comparing(Document::getScore)
                .thenComparing(Document::getId, Comparator.reverseOrder());
        PriorityQueue<Document> best = new PriorityQueue<>(worstFirst);
        jdbc.query("SELECT * FROM desktop_vector_store WHERE dimensions = ? AND embedding_version = ?", (RowCallbackHandler) rs -> {
            Row row = readRow(rs);
            if (!matches(request.getFilterExpression(), row.document().getMetadata())) return;
            double score = cosine(query, row.vector());
            if (score < request.getSimilarityThreshold()) return;
            best.add(row.document().mutate().score(score).build());
            if (best.size() > request.getTopK()) best.remove();
        }, dimensions, embeddingVersion);
        return best.stream().sorted(worstFirst.reversed()).toList();
    }

    private List<Row> allRows() {
        return jdbc.query("SELECT * FROM desktop_vector_store", (rs, index) -> readRow(rs));
    }

    private Row readRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        try {
            byte[] bytes = rs.getBytes("embedding");
            int storedDimensions = rs.getInt("dimensions");
            if (bytes.length != Math.multiplyExact(storedDimensions, Float.BYTES)) throw new IllegalStateException("Invalid vector data");
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            float[] vector = new float[storedDimensions];
            for (int i = 0; i < vector.length; i++) vector[i] = buffer.getFloat();
            Document document = Document.builder().id(rs.getString("id")).text(rs.getString("text"))
                    .metadata(mapper.readValue(rs.getString("metadata"), METADATA)).build();
            return new Row(document, vector, storedDimensions, rs.getString("embedding_version"));
        } catch (java.sql.SQLException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot read persistent vector data", failure);
        }
    }

    private void validate(float[] vector) {
        if (vector == null || vector.length != dimensions) throw new IllegalArgumentException("Embedding dimension mismatch");
        double squared = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite embedding");
            squared += (double) value * value;
        }
        if (squared == 0) throw new IllegalArgumentException("Zero embedding has no cosine direction");
    }

    private static byte[] encode(float[] vector) {
        ByteBuffer bytes = ByteBuffer.allocate(vector.length * Float.BYTES);
        for (float value : vector) bytes.putFloat(value);
        return bytes.array();
    }

    private double cosine(float[] left, float[] right) {
        validate(right);
        double dot = 0, a = 0, b = 0;
        for (int i = 0; i < dimensions; i++) {
            dot += (double) left[i] * right[i];
            a += (double) left[i] * left[i];
            b += (double) right[i] * right[i];
        }
        return Math.clamp(dot / (Math.sqrt(a) * Math.sqrt(b)), -1, 1);
    }

    static boolean matches(Filter.Expression expression, Map<String, Object> metadata) {
        return expression == null || Boolean.TRUE.equals(value(expression, metadata));
    }

    private static Object value(Filter.Operand operand, Map<String, Object> metadata) {
        if (operand instanceof Filter.Key key) return metadata.get(key.key());
        if (operand instanceof Filter.Value literal) return literal.value();
        if (operand instanceof Filter.Group group) return value(group.content(), metadata);
        if (!(operand instanceof Filter.Expression expression)) throw new IllegalArgumentException("Unsupported filter operand");
        Object left = value(expression.left(), metadata);
        if (expression.type() == Filter.ExpressionType.NOT) return left == null ? null : !Boolean.TRUE.equals(left);
        Object right = value(expression.right(), metadata);
        return switch (expression.type()) {
            case AND -> Boolean.FALSE.equals(left) || Boolean.FALSE.equals(right) ? false
                    : left == null || right == null ? null : true;
            case OR -> Boolean.TRUE.equals(left) || Boolean.TRUE.equals(right) ? true
                    : left == null || right == null ? null : false;
            case EQ -> left == null || right == null ? null : equal(left, right);
            case NE -> left == null || right == null ? null : !equal(left, right);
            case IN -> membership(left, right, false);
            case NIN -> membership(left, right, true);
            case GT, GTE, LT, LTE -> left == null || right == null ? null : ordered(expression.type(), left, right);
            case NOT -> throw new IllegalStateException("NOT handled above");
        };
    }

    private static Boolean membership(Object left, Object right, boolean negate) {
        if (left == null || right == null) return null;
        if (!(right instanceof Collection<?> values)) throw new IllegalArgumentException("IN requires a collection");
        if (values.stream().filter(Objects::nonNull).anyMatch(item -> equal(left, item))) return !negate;
        return values.contains(null) ? null : negate;
    }

    private static boolean equal(Object left, Object right) {
        return left instanceof Number a && right instanceof Number b
                ? new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0 : Objects.equals(left, right);
    }

    private static boolean ordered(Filter.ExpressionType operator, Object left, Object right) {
        if (left == null || right == null) return false;
        int comparison;
        if (left instanceof Number a && right instanceof Number b) comparison = new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()));
        else if (left instanceof String a && right instanceof String b) comparison = a.compareTo(b);
        else throw new IllegalArgumentException("Incompatible filter comparison");
        return switch (operator) {
            case GT -> comparison > 0;
            case GTE -> comparison >= 0;
            case LT -> comparison < 0;
            case LTE -> comparison <= 0;
            default -> throw new IllegalArgumentException("Invalid ordered comparison");
        };
    }

    private record Stored(String id, String text, String metadata, byte[] embedding) {}
    private record Row(Document document, float[] vector, int dimensions, String version) {}
}
