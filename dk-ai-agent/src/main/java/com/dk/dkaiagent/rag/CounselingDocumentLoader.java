package com.dk.dkaiagent.rag;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 心理咨询师应用文档加载器
 */
@Component
@Slf4j
public class CounselingDocumentLoader {

    static final String MANIFEST_LOCATION = "classpath*:counseling-kb/manifests/sources.jsonl";
    private static final String RUNTIME_PREFIX = "dk-ai-agent/src/main/resources/";
    private static final ObjectMapper MANIFEST_READER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> DOMAINS = Set.of("knowledge", "cases/summaries", "cases/transcripts",
            "policy", "prompt-references", "exam/practice");
    private static final Set<String> USES = Set.of("production", "evaluation", "reference", "quarantine");
    private static final Set<String> LICENSE_STATUSES = Set.of("unknown", "approved", "restricted", "rejected");
    private static final Set<String> PII_STATUSES = Set.of("unknown", "contains_pii", "redacted", "no_pii");
    private static final Set<String> REVIEW_STATUSES = Set.of("pending", "approved", "rejected");
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
            List<ApprovedSource> sources = approvedSources();
            for (ApprovedSource source : sources) {
                Resource[] resources = resourcePatternResolver.getResources("classpath*:" + source.classpathPath());
                if (resources == null || resources.length != 1 || !resources[0].exists()) {
                    throw new IOException("生产知识来源缺失或存在多个 classpath 副本: " + source.id());
                }
                allDocuments.addAll(loadResource(resources[0], source));
            }
            log.info("生产知识准入完成：已批准来源 {} 个，文档块 {} 个", sources.size(), allDocuments.size());
            if (sources.isEmpty()) {
                log.warn("生产知识集为空：清单中没有满足授权和审核门禁的来源");
            }
        } catch (IOException e) {
            throw new IllegalStateException("生产知识清单或文档加载失败，拒绝用不完整快照替换知识库", e);
        }
        return allDocuments;
    }

    private List<ApprovedSource> approvedSources() throws IOException {
        Resource[] manifests = resourcePatternResolver.getResources(MANIFEST_LOCATION);
        if (manifests == null || manifests.length != 1 || !manifests[0].exists()) {
            throw new IOException("生产知识清单缺失或存在多个 classpath 副本");
        }
        String manifest = manifests[0].getContentAsString(StandardCharsets.UTF_8);
        List<ApprovedSource> approved = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> runtimePaths = new HashSet<>();
        String[] lines = manifest.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.isBlank()) {
                continue;
            }
            JsonNode entry = MANIFEST_READER.readTree(line);
            if (entry == null || !entry.isObject()) {
                throw new IOException("知识清单第 " + (index + 1) + " 行必须为 JSON 对象");
            }
            String id = requiredText(entry, "id");
            if (!id.matches("[a-z0-9][a-z0-9._-]*") || !ids.add(id)) {
                throw new IOException("知识清单来源 ID 无效或重复: " + id);
            }
            requiredText(entry, "title");
            requiredText(entry, "license");
            requiredText(entry, "notes");
            String domain = enumText(entry, "domain", DOMAINS);
            String use = enumText(entry, "use", USES);
            String licenseStatus = enumText(entry, "license_status", LICENSE_STATUSES);
            String piiStatus = enumText(entry, "pii_status", PII_STATUSES);
            String reviewStatus = enumText(entry, "review_status", REVIEW_STATUSES);
            String runtimePath = optionalText(entry, "runtime_path");
            if (runtimePath != null && !runtimePaths.add(runtimePath)) {
                throw new IOException("知识清单 runtime_path 重复: " + id);
            }
            if (!"production".equals(use) || !"approved".equals(reviewStatus)
                    || !"approved".equals(licenseStatus)
                    || !("redacted".equals(piiStatus) || "no_pii".equals(piiStatus))
                    || "unknown".equalsIgnoreCase(requiredText(entry, "license"))
                    || optionalText(entry, "license_evidence") == null
                    || optionalText(entry, "reviewed_by") == null
                    || !validReviewDate(optionalText(entry, "reviewed_at"))) {
                continue;
            }
            String knowledgeDomain = switch (domain) {
                case "knowledge" -> KnowledgeDomain.KNOWLEDGE.getValue();
                case "cases/summaries" -> KnowledgeDomain.CASES.getValue();
                case "policy" -> KnowledgeDomain.POLICY.getValue();
                default -> null;
            };
            // Exam questions, raw transcripts and prompt references never enter counseling RAG.
            if (knowledgeDomain != null) {
                approved.add(new ApprovedSource(id, approvedClasspathPath(runtimePath), knowledgeDomain));
            }
        }
        approved.sort(Comparator.comparing(ApprovedSource::classpathPath));
        return approved;
    }

    private static String approvedClasspathPath(String runtimePath) throws IOException {
        if (runtimePath == null || !runtimePath.startsWith(RUNTIME_PREFIX)) {
            throw new IOException("生产来源必须通过 runtime_path 指向应用内 Markdown 文档");
        }
        String classpathPath = runtimePath.substring(RUNTIME_PREFIX.length());
        if (!classpathPath.matches("document/(?:professional/|policy/)?[^/]+\\.md")
                || classpathPath.chars().anyMatch(character -> Character.isISOControl(character)
                || "\\\\:%*?[]{}#!".indexOf(character) >= 0)
                || List.of(classpathPath.split("/")).stream().anyMatch(segment -> segment.equals(".") || segment.equals(".."))) {
            throw new IOException("生产来源 runtime_path 不在允许的 Markdown 文档范围内");
        }
        return classpathPath;
    }

    private static String enumText(JsonNode entry, String field, Set<String> allowed) throws IOException {
        String value = requiredText(entry, field);
        if (!allowed.contains(value)) {
            throw new IOException("知识清单字段值无效: " + field);
        }
        return value;
    }

    private static String requiredText(JsonNode entry, String field) throws IOException {
        String value = optionalText(entry, field);
        if (value == null) {
            throw new IOException("知识清单缺少非空字符串字段: " + field);
        }
        return value;
    }

    private static String optionalText(JsonNode entry, String field) throws IOException {
        JsonNode value = entry.get(field);
        if (value == null) {
            return null;
        }
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IOException("知识清单字段必须为非空字符串: " + field);
        }
        return value.textValue();
    }

    private static boolean validReviewDate(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            return false;
        }
        try {
            LocalDate.parse(value);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private record ApprovedSource(String id, String classpathPath, String domain) { }

    private List<Document> loadResource(Resource resource, ApprovedSource source) throws IOException {
        String filename = resource.getFilename();
        if (filename == null || filename.isBlank()) {
            throw new IOException("Markdown resource has no filename: " + resource.getDescription());
        }
        // Read once so the version and parsed chunks always describe the same snapshot.
        byte[] sourceBytes = resource.getContentAsByteArray();
        String markdown = new String(sourceBytes, StandardCharsets.UTF_8);
        String sourceId = "classpath:" + source.classpathPath();
        String documentId = sha256(source.domain() + "\u0000" + sourceId);
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
            metadata.put("knowledgeDomain", source.domain());
            metadata.put("manifestSourceId", source.id());
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
