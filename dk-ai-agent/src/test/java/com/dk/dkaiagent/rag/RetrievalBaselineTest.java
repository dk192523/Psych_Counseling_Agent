package com.dk.dkaiagent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Offline model-quality baseline: production loader/model, exact cosine (not pgvector ANN).
 * Opt-in because the initial ONNX model download and embedding corpus take time; no chat API calls.
 */
@EnabledIfEnvironmentVariable(named = "RUN_RAG_BASELINE", matches = "true")
class RetrievalBaselineTest {
    private static final Pattern SLUG = Pattern.compile("20\\d{2}-\\d{2}-\\d{2}-call-\\d{2}");
    @Test void recordProductionEmbeddingBaseline() throws Exception {
        var mapper = new ObjectMapper();
        var seed = mapper.readTree(Path.of("../eval/rag_seed.json").toFile());
        var documents = new CounselingDocumentLoader(new PathMatchingResourcePatternResolver()).loadMarkdowns();
        assertFalse(documents.isEmpty());
        var model = new TransformersEmbeddingModel();
        var yaml = new org.springframework.beans.factory.config.YamlPropertiesFactoryBean();
        yaml.setResources(new org.springframework.core.io.FileSystemResource("src/main/resources/application.yml"));
        var environment = new org.springframework.core.env.StandardEnvironment();
        environment.getPropertySources().addLast(new org.springframework.core.env.PropertiesPropertySource("app", yaml.getObject()));
        String modelUri = environment.getRequiredProperty("spring.ai.embedding.transformer.onnx.model-uri");
        model.setModelResource(modelUri);
        model.setTokenizerResource(environment.getRequiredProperty("spring.ai.embedding.transformer.tokenizer.uri"));
        model.afterPropertiesSet();
        var vectors = new ArrayList<float[]>();
        for (var doc : documents) vectors.add(model.embed(doc));
        var allSlugs = new HashSet<String>();
        for (var doc : documents) SLUG.matcher(doc.getText()).results().forEach(m -> allSlugs.add(m.group()));
        int positive = 0, hits = 0, negative = 0, rejected = 0;
        double reciprocal = 0;
        var results = new ArrayList<Map<String, Object>>();
        for (var c : seed.get("cases")) {
            String query = c.get("query").asText();
            var expected = new HashSet<String>();
            c.get("expected").forEach(s -> expected.add(s.asText()));
            assertTrue(allSlugs.containsAll(expected), "Label missing from corpus: " + expected);
            float[] q = model.embed(query);
            var ranked = new ArrayList<Scored>();
            for (int i = 0; i < documents.size(); i++) {
                double score = cosine(q, vectors.get(i));
                if (score >= 0.3) ranked.add(new Scored(i, score));
            }
            ranked.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparingInt(Scored::index));
            var top = ranked.stream().limit(4).toList();
            int first = 0;
            var candidates = new ArrayList<Map<String, Object>>();
            for (int rank = 0; rank < top.size(); rank++) {
                var match = top.get(rank);
                var slugs = SLUG.matcher(documents.get(match.index()).getText()).results().map(m -> m.group()).distinct().toList();
                if (first == 0 && slugs.stream().anyMatch(expected::contains)) first = rank + 1;
                candidates.add(Map.of("rank", rank + 1, "score", match.score(), "slugs", slugs,
                        "filename", documents.get(match.index()).getMetadata().get("filename")));
            }
            if (expected.isEmpty()) { negative++; if (top.isEmpty()) rejected++; }
            else { positive++; if (first > 0) { hits++; reciprocal += 1.0 / first; } }
            results.add(Map.of("id", c.get("id").asText(), "query", query, "expected", expected, "first_relevant_rank", first, "candidates", candidates));
        }
        var report = new LinkedHashMap<String, Object>();
        report.put("timestamp", Instant.now().toString());
        report.put("label_status", seed.get("label_status").asText());
        report.put("scope", "production Markdown loader and Transformers embedding; exact cosine; excludes pgvector ANN, deep planning/reranking and chat generation");
        report.put("model_uri", modelUri == null || modelUri.isBlank() ? TransformersEmbeddingModel.DEFAULT_ONNX_MODEL_URI : modelUri);
        report.put("dimensions", vectors.getFirst().length);
        report.put("corpus_fingerprint", PgVectorVectorStoreConfig.calculateKnowledgeBaseVersion(documents));
        report.put("documents", documents.size());
        report.put("top_k", 4); report.put("threshold", 0.3);
        report.put("positive_cases", positive); report.put("negative_cases", negative);
        report.put("recall_at_4", (double) hits / positive);
        report.put("mrr_at_4", reciprocal / positive);
        report.put("no_hit_accuracy", (double) rejected / negative);
        report.put("results", results);
        Files.writeString(Path.of("../eval/rag_baseline.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        // No invented quality threshold: this first measured run establishes the starting point.
        assertEquals(30, results.size());
    }
    record Scored(int index, double score) {}
    private static double cosine(float[] a, float[] b) {
        double dot = 0, aa = 0, bb = 0;
        for (int i = 0; i < a.length; i++) { dot += (double) a[i] * b[i]; aa += (double) a[i] * a[i]; bb += (double) b[i] * b[i]; }
        return aa == 0 || bb == 0 ? 0 : dot / Math.sqrt(aa * bb);
    }
}
