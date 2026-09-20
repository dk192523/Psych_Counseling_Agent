package com.dk.dkaiagent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Source-grounded seed regression, not an independently annotated golden set.
 * Production loader/model and exact cosine; excludes pgvector ANN and chat calls.
 */
@EnabledIfEnvironmentVariable(named = "RUN_RAG_BASELINE", matches = "true")
class RetrievalBaselineTest {
    private static final Pattern SLUG = Pattern.compile("20\\d{2}-\\d{2}-\\d{2}-call-\\d{2}");
    private static final int TOP_K = 4;
    private static final double THRESHOLD = 0.3;

    @Test void recordProductionEmbeddingBaseline() throws Exception {
        long started = System.nanoTime();
        var mapper = new ObjectMapper();
        byte[] seedBytes = Files.readAllBytes(Path.of("../eval/rag_seed.json"));
        var seed = mapper.readTree(seedBytes);
        var cases = validateSeed(seed);
        var documents = new ArrayList<>(new CounselingDocumentLoader(new PathMatchingResourcePatternResolver()).loadMarkdowns());
        assertFalse(documents.isEmpty(), "Corpus must not be empty");
        // Canonical source/text order avoids loader order and random Document IDs in ties.
        documents.sort(Comparator.comparing(RetrievalBaselineTest::documentSourceKey).thenComparing(Document::getText));
        var documentSlugs = new ArrayList<List<String>>();
        var documentKeys = new ArrayList<String>();
        var allSlugs = new HashSet<String>();
        for (var doc : documents) {
            var slugs = SLUG.matcher(doc.getText()).results().map(m -> m.group()).distinct().sorted().toList();
            documentSlugs.add(slugs);
            allSlugs.addAll(slugs);
            documentKeys.add(sha256((documentSourceKey(doc) + "\u0000" + doc.getText()).getBytes(StandardCharsets.UTF_8)));
        }
        for (var c : cases) assertTrue(allSlugs.containsAll(c.expected()), "Expected slug missing from corpus for " + c.id());

        var model = new TransformersEmbeddingModel();
        var yaml = new org.springframework.beans.factory.config.YamlPropertiesFactoryBean();
        yaml.setResources(new org.springframework.core.io.FileSystemResource("src/main/resources/application.yml"));
        var environment = new org.springframework.core.env.StandardEnvironment();
        environment.getPropertySources().addLast(new org.springframework.core.env.PropertiesPropertySource("app", yaml.getObject()));
        String modelUri = environment.getRequiredProperty("spring.ai.embedding.transformer.onnx.model-uri");
        String tokenizerUri = environment.getRequiredProperty("spring.ai.embedding.transformer.tokenizer.uri");
        model.setModelResource(modelUri);
        model.setTokenizerResource(tokenizerUri);
        model.afterPropertiesSet();
        var vectors = new ArrayList<float[]>();
        for (var doc : documents) vectors.add(model.embed(doc));

        int positive = 0, documentHits = 0, caseHits = 0, positiveRejected = 0;
        int negative = 0, negativeRejected = 0, unjudged = 0;
        double documentReciprocal = 0, caseReciprocal = 0, caseRecall = 0;
        var results = new ArrayList<Map<String, Object>>();
        for (var c : cases) {
            long queryStarted = System.nanoTime();
            float[] q = model.embed(c.query());
            var ranked = new ArrayList<Scored>();
            for (int i = 0; i < documents.size(); i++) {
                double score = cosine(q, vectors.get(i));
                assertTrue(Double.isFinite(score), "Non-finite similarity for " + c.id());
                if (score >= THRESHOLD) ranked.add(new Scored(i, score));
            }
            ranked.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparingInt(Scored::index));
            var topDocuments = ranked.stream().limit(TOP_K).toList();
            int firstDocument = 0;
            var candidates = new ArrayList<Map<String, Object>>();
            for (int rank = 0; rank < topDocuments.size(); rank++) {
                var match = topDocuments.get(rank);
                var slugs = documentSlugs.get(match.index());
                if (firstDocument == 0 && slugs.stream().anyMatch(c.expected()::contains)) firstDocument = rank + 1;
                candidates.add(Map.of("rank", rank + 1, "score", match.score(), "slugs", slugs,
                        "document_key", documentKeys.get(match.index()),
                        "filename", String.valueOf(documents.get(match.index()).getMetadata().getOrDefault("filename", ""))));
            }
            // Merge ALL threshold-qualified Documents before case topK; a case uses its maximum score.
            var bestBySlug = new HashMap<String, Scored>();
            for (var match : ranked) {
                for (var slug : documentSlugs.get(match.index())) bestBySlug.putIfAbsent(slug, match);
            }
            var topCases = bestBySlug.entrySet().stream()
                    .sorted(Comparator.<Map.Entry<String, Scored>>comparingDouble(e -> e.getValue().score())
                            .reversed().thenComparing(Map.Entry::getKey))
                    .limit(TOP_K).toList();
            int firstCase = 0, matchedExpected = 0;
            var caseCandidates = new ArrayList<Map<String, Object>>();
            for (int rank = 0; rank < topCases.size(); rank++) {
                var match = topCases.get(rank);
                if (c.expected().contains(match.getKey())) {
                    matchedExpected++;
                    if (firstCase == 0) firstCase = rank + 1;
                }
                caseCandidates.add(Map.of("rank", rank + 1, "slug", match.getKey(),
                        "score", match.getValue().score(), "best_document_key", documentKeys.get(match.getValue().index())));
            }
            switch (c.sampleType()) {
                case "positive" -> {
                    positive++;
                    if (topDocuments.isEmpty()) positiveRejected++;
                    if (firstDocument > 0) { documentHits++; documentReciprocal += 1.0 / firstDocument; }
                    if (firstCase > 0) { caseHits++; caseReciprocal += 1.0 / firstCase; }
                    caseRecall += divide(matchedExpected, c.expected().size());
                }
                case "negative" -> { negative++; if (topDocuments.isEmpty()) negativeRejected++; }
                case "unjudged" -> unjudged++;
                default -> throw new IllegalStateException("Seed validation missed sample_type");
            }
            var result = new LinkedHashMap<String, Object>();
            result.put("id", c.id());
            result.put("sample_type", c.sampleType());
            result.put("expected", c.expected());
            result.put("first_relevant_rank", firstDocument); // Legacy Document-rank alias.
            result.put("first_relevant_document_rank", firstDocument);
            result.put("first_relevant_case_rank", firstCase);
            result.put("matched_expected_cases", matchedExpected);
            result.put("candidates", candidates);
            result.put("case_candidates", caseCandidates);
            result.put("query_elapsed_ms", elapsedMillis(queryStarted));
            results.add(result); // Do not export corpus text or query text.
        }
        var report = new LinkedHashMap<String, Object>();
        report.put("report_schema_version", 2);
        report.put("measurement_status", "measured");
        report.put("timestamp", Instant.now().toString());
        report.put("seed_schema_version", seed.get("schema_version").intValue());
        report.put("seed_sha256", sha256(seedBytes));
        report.put("label_status", seed.get("label_status").asText());
        report.put("scope", "source-grounded seed regression; production Markdown loader and Transformers embedding; exact cosine; excludes pgvector ANN, deep planning/reranking and chat generation");
        report.put("model_uri", modelUri);
        report.put("tokenizer_uri", tokenizerUri);
        report.put("dimensions", vectors.getFirst().length);
        report.put("corpus_fingerprint", PgVectorVectorStoreConfig.calculateKnowledgeBaseVersion(documents));
        report.put("documents", documents.size());
        report.put("top_k", TOP_K);
        report.put("threshold", THRESHOLD);
        report.put("document_tie_break", "score DESC, filename ASC, status ASC, full text ASC (Java String order); identical source/text Documents are interchangeable");
        report.put("case_ranking", "max score per slug over all threshold-qualified Documents; score DESC, slug ASC; deduplicate before topK");
        report.put("mrr_ranking_unit", "case");
        report.put("positive_cases", positive);
        report.put("negative_cases", negative);
        report.put("unjudged_cases", unjudged);
        report.put("document_hit_at_4", divide(documentHits, positive));
        report.put("recall_at_4", divide(documentHits, positive)); // Deprecated name; never Recall.
        report.put("document_mrr_at_4", divide(documentReciprocal, positive));
        report.put("case_hit_at_4", divide(caseHits, positive));
        report.put("case_recall_at_4", divide(caseRecall, positive));
        report.put("mrr_at_4", divide(caseReciprocal, positive));
        report.put("positive_rejection_rate", divide(positiveRejected, positive));
        report.put("negative_false_hit_rate", divide(negative - negativeRejected, negative));
        report.put("no_hit_accuracy", divide(negativeRejected, negative));
        report.put("compatibility", Map.of("recall_at_4", "deprecated alias of document_hit_at_4, never Recall",
                "no_hit_accuracy", "negative rejection rate", "first_relevant_rank", "deprecated document rank alias",
                "mrr_at_4", "schema v2 uses case ranks; schema v1 used Document ranks, now document_mrr_at_4"));
        report.put("results", results);
        report.put("total_elapsed_ms", elapsedMillis(started)); // Excludes report serialization/write.
        Files.writeString(Path.of("../eval/rag_baseline.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);
    }

    private static List<SeedCase> validateSeed(JsonNode seed) {
        assertTrue(seed != null && seed.isObject(), "Seed must be an object");
        assertTrue(seed.path("schema_version").isIntegralNumber(), "schema_version must be an integer");
        assertEquals(2, seed.path("schema_version").intValue(), "Unsupported seed schema");
        requiredText(seed, "label_status", "seed");
        assertTrue(seed.path("cases").isArray(), "cases must be an array");
        assertEquals(30, seed.get("cases").size(), "Keep the 30 source-grounded regression seeds");
        assertTrue(seed.path("sample_counts").isObject(), "sample_counts must declare positive/negative/unjudged counts");
        var counts = new TreeMap<>(Map.of("positive", 0, "negative", 0, "unjudged", 0));
        var ids = new HashSet<String>();
        var cases = new ArrayList<SeedCase>();
        for (var c : seed.get("cases")) {
            assertTrue(c.isObject(), "Each seed case must be an object");
            String id = requiredText(c, "id", "case");
            assertEquals(id.strip(), id, "id must not have surrounding whitespace");
            assertTrue(ids.add(id), "Duplicate seed id: " + id);
            String query = requiredText(c, "query", id);
            String sampleType = requiredText(c, "sample_type", id);
            assertTrue(counts.containsKey(sampleType), "Unknown sample_type for " + id);
            assertTrue(c.path("expected").isArray(), "expected must be an array for " + id);
            var expected = new TreeSet<String>();
            for (var slug : c.get("expected")) {
                assertTrue(slug.isTextual() && SLUG.matcher(slug.asText()).matches(), "Invalid expected slug for " + id);
                expected.add(slug.asText()); // Deduplicate multi-label denominators.
            }
            if (sampleType.equals("positive")) assertFalse(expected.isEmpty(), "Positive seed requires expected slugs: " + id);
            else assertTrue(expected.isEmpty(), "Negative/unjudged seed requires empty expected: " + id);
            counts.compute(sampleType, (key, value) -> value + 1);
            cases.add(new SeedCase(id, query, List.copyOf(expected), sampleType));
        }
        counts.forEach((type, count) -> {
            var declared = seed.get("sample_counts").path(type);
            assertTrue(declared.isIntegralNumber() && declared.canConvertToInt() && declared.intValue() >= 0,
                    "sample_counts." + type + " must be a nonnegative integer");
            assertEquals(count.intValue(), declared.intValue(), "sample_counts mismatch for " + type);
        });
        return cases;
    }

    private static String requiredText(JsonNode node, String field, String context) {
        assertTrue(node.path(field).isTextual() && !node.path(field).asText().isBlank(), context + ": missing/non-text/blank " + field);
        return node.get(field).asText();
    }
    private static String documentSourceKey(Document document) {
        return String.valueOf(document.getMetadata().getOrDefault("filename", "")) + "\u0000"
                + String.valueOf(document.getMetadata().getOrDefault("status", ""));
    }
    private static String sha256(byte[] input) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
    }
    private static double divide(double numerator, int denominator) { return denominator == 0 ? 0.0 : numerator / denominator; }
    private static double elapsedMillis(long started) { return (System.nanoTime() - started) / 1_000_000.0; }
    record SeedCase(String id, String query, List<String> expected, String sampleType) {}
    record Scored(int index, double score) {}
    private static double cosine(float[] a, float[] b) {
        assertEquals(a.length, b.length, "Embedding dimensions must agree");
        double dot = 0, aa = 0, bb = 0;
        for (int i = 0; i < a.length; i++) { dot += (double) a[i] * b[i]; aa += (double) a[i] * a[i]; bb += (double) b[i] * b[i]; }
        return aa == 0 || bb == 0 ? 0 : dot / Math.sqrt(aa * bb);
    }
}
