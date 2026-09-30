package com.dk.dkaiagent.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CounselingDocumentLoaderUnitTest {

    @Test
    void legacySourcesHaveStableMetadataAndSupportShortFilenames() throws IOException {
        String markdown = "Source-URL: https://example.org/source\nStatus: published\n\n正文。\n\n---\n\n第二块。";
        CounselingDocumentLoader loader = loader(resource("a.md", markdown));

        List<Document> first = loader.loadMarkdowns();
        List<Document> second = loader.loadMarkdowns();

        assertEquals(2, first.size());
        for (int index = 0; index < first.size(); index++) {
            var metadata = first.get(index).getMetadata();
            assertEquals(metadata, second.get(index).getMetadata());
            assertEquals("psych-cases", metadata.get("knowledgeDomain"));
            assertEquals("classpath:document/a.md", metadata.get("sourceId"));
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
        var before = loader(resource("a.md", "原文")).loadMarkdowns().getFirst().getMetadata();
        var after = loader(resource("a.md", "修改后的正文")).loadMarkdowns().getFirst().getMetadata();

        assertEquals(before.get("documentId"), after.get("documentId"));
        assertNotEquals(before.get("sourceVersion"), after.get("sourceVersion"));
        assertNotEquals(before.get("contentHash"), after.get("contentHash"));
        assertNotEquals(before.get("chunkId"), after.get("chunkId"));
        assertEquals("active", before.get("status"));
        assertFalse(before.containsKey("citationUrl"));
    }

    @Test
    void identicalChunksAndDistinctSourcesHaveDistinctIdentities() throws IOException {
        List<Document> chunks = loader(resource("b.md", "重复\n\n---\n\n重复"),
                resource("a.md", "重复")).loadMarkdowns();

        assertEquals("a.md", chunks.getFirst().getMetadata().get("filename"));
        assertEquals(3, chunks.size());
        assertEquals(3, chunks.stream().map(chunk -> chunk.getMetadata().get("chunkId")).distinct().count());
        assertEquals(2, chunks.stream().map(chunk -> chunk.getMetadata().get("documentId")).distinct().count());
    }

    @Test
    void sha256UsesTheStandardUtf8Digest() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                CounselingDocumentLoader.sha256("abc"));
    }

    private static CounselingDocumentLoader loader(Resource... resources) throws IOException {
        ResourcePatternResolver resolver = mock(ResourcePatternResolver.class);
        when(resolver.getResources("classpath:document/*.md")).thenReturn(resources);
        return new CounselingDocumentLoader(resolver);
    }

    private static Resource resource(String filename, String markdown) {
        return new ByteArrayResource(markdown.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }
}
