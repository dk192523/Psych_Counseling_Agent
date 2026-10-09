package com.dk.dkaiagent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CounselingDocumentLoaderUnitTest {

    @Test
    void approvedProductionSourcesHaveStableMetadataAndSupportShortFilenames() throws IOException {
        String markdown = "Source-URL: https://example.org/source\nStatus: published\n\n正文。\n\n---\n\n第二块。";
        CounselingDocumentLoader loader = loader(approved("a.md").toString(), resource("a.md", markdown));
        List<Document> first = loader.loadMarkdowns();
        List<Document> second = loader.loadMarkdowns();
        assertEquals(2, first.size());
        for (int index = 0; index < first.size(); index++) {
            var metadata = first.get(index).getMetadata();
            assertEquals(metadata, second.get(index).getMetadata());
            assertEquals("psych-cases", metadata.get("knowledgeDomain"));
            assertEquals("classpath:document/a.md", metadata.get("sourceId"));
            assertEquals("a.md", metadata.get("manifestSourceId"));
            assertEquals("a.md", metadata.get("filename"));
            assertEquals("published", metadata.get("status"));
            assertEquals("https://example.org/source", metadata.get("citationUrl"));
            assertEquals(CounselingDocumentLoader.sha256(markdown), metadata.get("sourceVersion"));
            assertEquals(CounselingDocumentLoader.sha256(first.get(index).getText()), metadata.get("contentHash"));
            assertTrue(metadata.get("documentId").toString().matches("[0-9a-f]{64}"));
            assertTrue(metadata.get("chunkId").toString().matches("[0-9a-f]{64}"));
        }
        assertNotEquals(first.get(0).getMetadata().get("chunkId"), first.get(1).getMetadata().get("chunkId"));
    }

    @Test
    void editedSourceKeepsDocumentIdentityButChangesVersionAndChunkIdentity() throws IOException {
        String manifest = approved("a.md").toString();
        var before = loader(manifest, resource("a.md", "原文")).loadMarkdowns().getFirst().getMetadata();
        var after = loader(manifest, resource("a.md", "修改后的正文")).loadMarkdowns().getFirst().getMetadata();
        assertEquals(before.get("documentId"), after.get("documentId"));
        assertNotEquals(before.get("sourceVersion"), after.get("sourceVersion"));
        assertNotEquals(before.get("contentHash"), after.get("contentHash"));
        assertNotEquals(before.get("chunkId"), after.get("chunkId"));
        assertEquals("active", before.get("status"));
        assertFalse(before.containsKey("citationUrl"));
    }

    @Test
    void identicalChunksAndDistinctSourcesHaveDistinctIdentities() throws IOException {
        List<Document> chunks = loader(approved("b.md") + "\n" + approved("a.md"),
                resource("b.md", "重复\n\n---\n\n重复"), resource("a.md", "重复")).loadMarkdowns();
        assertEquals("a.md", chunks.getFirst().getMetadata().get("filename"));
        assertEquals(3, chunks.size());
        assertEquals(3, chunks.stream().map(chunk -> chunk.getMetadata().get("chunkId")).distinct().count());
        assertEquals(2, chunks.stream().map(chunk -> chunk.getMetadata().get("documentId")).distinct().count());
    }

    @Test
    void pendingQuarantineReferenceAndEvaluationNeverResolveDocuments() throws IOException {
        assertExcluded(approved("pending.md").put("review_status", "pending") + "\n"
                + approved("quarantine.md").put("use", "quarantine") + "\n"
                + approved("reference.md").put("use", "reference") + "\n"
                + approved("evaluation.md").put("use", "evaluation"));
    }

    @Test
    void incompleteAuthorizationOrReviewNeverAdmitsASource() throws IOException {
        for (String field : List.of("license_evidence", "reviewed_by", "reviewed_at")) {
            ObjectNode row = approved("a.md");
            row.remove(field);
            assertExcluded(row.toString());
        }
        for (ObjectNode row : List.of(approved("a.md").put("license_status", "unknown"),
                approved("a.md").put("license", "unknown"),
                approved("a.md").put("pii_status", "contains_pii"),
                approved("a.md").put("reviewed_at", "2026-02-30"))) {
            assertExcluded(row.toString());
        }
    }

    @Test
    void approvedKnowledgeAndPolicyResolveOnlyExplicitClasspathFiles() throws IOException {
        ObjectNode knowledge = approved("knowledge.md").put("domain", "knowledge")
                .put("runtime_path", "dk-ai-agent/src/main/resources/document/professional/knowledge.md")
                .put("pii_status", "no_pii");
        ObjectNode policy = approved("policy.md").put("domain", "policy")
                .put("runtime_path", "dk-ai-agent/src/main/resources/document/policy/policy.md");
        ResourcePatternResolver resolver = resolver(knowledge + "\n" + policy);
        when(resolver.getResources("classpath*:document/professional/knowledge.md"))
                .thenReturn(new Resource[]{resource("knowledge.md", "知识内容")});
        when(resolver.getResources("classpath*:document/policy/policy.md"))
                .thenReturn(new Resource[]{resource("policy.md", "政策内容")});
        List<Document> documents = new CounselingDocumentLoader(resolver).loadMarkdowns();
        assertEquals(2, documents.size());
        assertEquals(List.of("psych-policy", "psych-knowledge"), documents.stream()
                .map(document -> document.getMetadata().get("knowledgeDomain")).toList());
        verify(resolver).getResources(CounselingDocumentLoader.MANIFEST_LOCATION);
        verify(resolver).getResources("classpath*:document/professional/knowledge.md");
        verify(resolver).getResources("classpath*:document/policy/policy.md");
        verifyNoMoreInteractions(resolver);
    }

    @Test
    void examsRawTranscriptsAndPromptDomainsRemainExcludedEvenIfApproved() throws IOException {
        for (String domain : List.of("exam/practice", "cases/transcripts", "prompt-references")) {
            assertExcluded(approved("a.md").put("domain", domain).toString());
        }
    }

    @Test
    void missingOrAmbiguousManifestFailsClosed() throws IOException {
        ResourcePatternResolver resolver = mock(ResourcePatternResolver.class);
        when(resolver.getResources(CounselingDocumentLoader.MANIFEST_LOCATION)).thenReturn(new Resource[0]);
        assertThrows(IllegalStateException.class, () -> new CounselingDocumentLoader(resolver).loadMarkdowns());
        when(resolver.getResources(CounselingDocumentLoader.MANIFEST_LOCATION))
                .thenReturn(new Resource[]{resource("sources.jsonl", ""), resource("sources.jsonl", "")});
        assertThrows(IllegalStateException.class, () -> new CounselingDocumentLoader(resolver).loadMarkdowns());
    }

    @Test
    void missingOrAmbiguousApprovedDocumentFailsClosed() throws IOException {
        ResourcePatternResolver resolver = resolver(approved("a.md").toString());
        when(resolver.getResources("classpath*:document/a.md")).thenReturn(new Resource[0]);
        assertThrows(IllegalStateException.class, () -> new CounselingDocumentLoader(resolver).loadMarkdowns());
        when(resolver.getResources("classpath*:document/a.md"))
                .thenReturn(new Resource[]{resource("a.md", "甲"), resource("a.md", "乙")});
        assertThrows(IllegalStateException.class, () -> new CounselingDocumentLoader(resolver).loadMarkdowns());
    }

    @Test
    void duplicateIdsOrRuntimePathsFailBeforeAnyDocumentLoad() throws IOException {
        ObjectNode first = approved("a.md");
        assertRejected(first + "\n" + approved("b.md").put("id", "a.md"));
        assertRejected(first + "\n" + approved("b.md").put("runtime_path", first.get("runtime_path").asText()));
    }

    @Test
    void externalEscapingWildcardAndNonMarkdownPathsFailClosed() throws IOException {
        for (String path : List.of("file:/tmp/a.md", "https://example.org/a.md", "classpath:document/a.md",
                "D:/private/a.md", "dk-ai-agent/src/main/resources/document/../private/a.md",
                "dk-ai-agent/src/main/resources/document/professional/../../a.md",
                "dk-ai-agent/src/main/resources/document\\a.md",
                "dk-ai-agent/src/main/resources/document/%2e%2e/a.md",
                "dk-ai-agent/src/main/resources/document/*.md",
                "dk-ai-agent/src/main/resources/document/a.jsonl",
                "dk-ai-agent/src/main/resources/exam/practice/a.md",
                "dk-ai-agent/src/main/resources/document/exam/a.md")) {
            assertRejected(approved("a.md").put("runtime_path", path).toString());
        }
    }

    @Test
    void approvedSourceCannotFallBackToLegacyPathOrSourceUrl() throws IOException {
        ObjectNode row = approved("a.md").put("path", "document/a.md")
                .put("source_url", "https://example.org/a.md");
        row.remove("runtime_path");
        assertRejected(row.toString());
    }

    @Test
    void corruptManifestFailsBeforeLoadingAnyApprovedSource() throws IOException {
        for (String corrupt : List.of("{broken", "[]", "{}", "null", "{} {}",
                approved("a.md").toString().replace("\"use\":\"production\"",
                        "\"use\":\"production\",\"use\":\"reference\""))) {
            assertRejected(approved("valid.md") + "\n" + corrupt);
        }
    }

    @Test
    void emptyValidManifestReturnsEmptyKnowledgeSet() throws IOException {
        assertExcluded("\n\n");
    }

    @Test
    void sha256UsesTheStandardUtf8Digest() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                CounselingDocumentLoader.sha256("abc"));
    }

    private static void assertExcluded(String manifest) throws IOException {
        ResourcePatternResolver resolver = resolver(manifest);
        assertTrue(new CounselingDocumentLoader(resolver).loadMarkdowns().isEmpty(), manifest);
        verify(resolver).getResources(CounselingDocumentLoader.MANIFEST_LOCATION);
        verifyNoMoreInteractions(resolver);
    }

    private static void assertRejected(String manifest) throws IOException {
        ResourcePatternResolver resolver = resolver(manifest);
        assertThrows(IllegalStateException.class, () -> new CounselingDocumentLoader(resolver).loadMarkdowns(), manifest);
        verify(resolver).getResources(CounselingDocumentLoader.MANIFEST_LOCATION);
        verifyNoMoreInteractions(resolver);
    }

    private static ObjectNode approved(String filename) {
        return new ObjectMapper().createObjectNode().put("id", filename).put("title", "审核文档")
                .put("domain", "cases/summaries").put("use", "production")
                .put("license", "项目原创；已核验生产用途").put("license_status", "approved")
                .put("license_evidence", "review/authorization-2026-10-09").put("pii_status", "redacted")
                .put("review_status", "approved").put("reviewed_by", "knowledge-reviewer")
                .put("reviewed_at", "2026-10-09").put("notes", "仅作为单元测试来源")
                .put("runtime_path", "dk-ai-agent/src/main/resources/document/" + filename);
    }

    private static CounselingDocumentLoader loader(String manifest, Resource... resources) throws IOException {
        ResourcePatternResolver resolver = resolver(manifest);
        for (Resource resource : resources) {
            when(resolver.getResources("classpath*:document/" + resource.getFilename()))
                    .thenReturn(new Resource[]{resource});
        }
        return new CounselingDocumentLoader(resolver);
    }

    private static ResourcePatternResolver resolver(String manifest) throws IOException {
        ResourcePatternResolver resolver = mock(ResourcePatternResolver.class);
        when(resolver.getResources(CounselingDocumentLoader.MANIFEST_LOCATION))
                .thenReturn(new Resource[]{resource("sources.jsonl", manifest)});
        return resolver;
    }

    private static Resource resource(String filename, String text) {
        return new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }
}
