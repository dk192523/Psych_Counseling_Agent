package com.dk.dkaiagent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 心理咨询师应用文档加载器
 */
@Component
@Slf4j
public class CounselingDocumentLoader {

    private static final Pattern SOURCE_URL = Pattern.compile(
            "(?im)^\\h*Source-URL:\\h*(https?://\\S+)\\h*$");
    private static final Pattern STATUS = Pattern.compile("(?im)^\\h*Status:\\h*([^\\r\\n]+)$");

    private final ResourcePatternResolver resourcePatternResolver;

    public CounselingDocumentLoader(ResourcePatternResolver resourcePatternResolver) {
        this.resourcePatternResolver = resourcePatternResolver;
    }

    /**
     * 加载多篇 Markdown 文档
     * @return
     */
    public List<Document> loadMarkdowns() {
        List<Document> allDocuments = new ArrayList<>();
        try {
            loadDirectory(allDocuments, "classpath:document/", KnowledgeDomain.CASES);
            loadDirectory(allDocuments, "classpath:document/professional/", KnowledgeDomain.KNOWLEDGE);
            loadDirectory(allDocuments, "classpath:document/policy/", KnowledgeDomain.POLICY);
        } catch (IOException e) {
            throw new IllegalStateException("Markdown 文档加载失败，拒绝用不完整快照替换知识库", e);
        }
        return allDocuments;
    }

    private void loadDirectory(List<Document> documents, String directory, KnowledgeDomain domain) throws IOException {
        Resource[] resources = resourcePatternResolver.getResources(directory + "*.md");
        if (resources == null) {
            return;
        }
        Arrays.sort(resources, Comparator.comparing(resource -> String.valueOf(resource.getFilename())));
        for (Resource resource : resources) {
            documents.addAll(loadResource(resource, directory, domain.getValue()));
        }
    }

    private List<Document> loadResource(Resource resource, String directory, String domain) throws IOException {
        String filename = resource.getFilename();
        if (filename == null || filename.isBlank()) {
            throw new IOException("Markdown resource has no filename: " + resource.getDescription());
        }
        // Read once so the version and parsed chunks always describe the same snapshot.
        byte[] sourceBytes = resource.getContentAsByteArray();
        String markdown = new String(sourceBytes, StandardCharsets.UTF_8);
        String sourceId = directory + filename;
        String documentId = sha256(domain + "\u0000" + sourceId);
        String sourceVersion = sha256(markdown);
        String status = metadataValue(STATUS, markdown, "active");
        String citationUrl = metadataValue(SOURCE_URL, markdown, null);
        MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                .withHorizontalRuleCreateDocument(true)
                .withIncludeCodeBlock(false)
                .withIncludeBlockquote(false)
                .withAdditionalMetadata("filename", filename)
                .build();
        List<Document> chunks = new MarkdownDocumentReader(new ByteArrayResource(sourceBytes), config).get();
        for (int index = 0; index < chunks.size(); index++) {
            Document chunk = chunks.get(index);
            String contentHash = sha256(chunk.getText());
            Map<String, Object> metadata = chunk.getMetadata();
            metadata.put("knowledgeDomain", domain);
            metadata.put("sourceId", sourceId);
            metadata.put("documentId", documentId);
            // The ordinal keeps identical repeated chunks distinct within one document.
            metadata.put("chunkId", sha256(documentId + "\u0000" + index + "\u0000" + contentHash));
            metadata.put("sourceVersion", sourceVersion);
            metadata.put("contentHash", contentHash);
            metadata.put("status", status);
            String chunkCitationUrl = metadataValue(SOURCE_URL, chunk.getText(), citationUrl);
            if (chunkCitationUrl != null) {
                metadata.put("citationUrl", chunkCitationUrl);
            }
        }
        return chunks;
    }

    static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }

    private static String metadataValue(Pattern pattern, String markdown, String defaultValue) {
        Matcher matcher = pattern.matcher(markdown);
        return matcher.find() ? matcher.group(1).strip() : defaultValue;
    }
}
