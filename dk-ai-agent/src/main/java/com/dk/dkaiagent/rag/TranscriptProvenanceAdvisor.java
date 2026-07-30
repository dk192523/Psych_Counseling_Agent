package com.dk.dkaiagent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Second-stage RAG advisor: resolves case-summary hits to timestamped raw
 * transcript excerpts and appends them to the model context.
 */
@Component
@Slf4j
public class TranscriptProvenanceAdvisor implements BaseAdvisor {

    public static final String ORIGINAL_QUERY = "transcript_original_query";
    public static final String RETRIEVED_SOURCES = "transcript_retrieved_sources";

    private static final Pattern CASE_SLUG_PATTERN = Pattern.compile(
            "案例编号\\s+(\\d{4}-\\d{2}-\\d{2}-call-\\d{2})"
    );
    private static final int ORDER_AFTER_QUESTION_ANSWER_ADVISOR = 1;
    private static final int SNIPPETS_PER_CASE = 2;
    private static final int MAX_CASES = 4;
    private static final int MAX_CONTEXT_CHARS = 5_000;

    private final TranscriptSearchService transcriptSearchService;

    public TranscriptProvenanceAdvisor(TranscriptSearchService transcriptSearchService) {
        this.transcriptSearchService = transcriptSearchService;
    }

    @Override
    public int getOrder() {
        return ORDER_AFTER_QUESTION_ANSWER_ADVISOR;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain advisorChain) {
        Object retrievedValue = request.context().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
        if (!(retrievedValue instanceof List<?> retrievedItems) || retrievedItems.isEmpty()) {
            return request;
        }

        String query = Objects.toString(
                request.context().getOrDefault(ORIGINAL_QUERY, request.prompt().getUserMessage().getText()),
                ""
        );
        if (query.isBlank()) {
            return request;
        }

        List<Document> documents = retrievedItems.stream()
                .filter(Document.class::isInstance)
                .map(Document.class::cast)
                .toList();
        List<TranscriptSearchService.TranscriptSource> sources = findSources(documents, query);
        if (sources.isEmpty()) {
            return request;
        }

        String contextText = buildContext(sources);
        Map<String, Object> context = new HashMap<>(request.context());
        context.put(RETRIEVED_SOURCES, sources);
        log.debug("Added raw transcript provenance for cases {}",
                sources.stream().map(TranscriptSearchService.TranscriptSource::slug).toList());
        String existingUserText = request.prompt().getUserMessage().getText();
        String enrichedUserText = existingUserText + "\n\n" + contextText;

        return request.mutate()
                .prompt(request.prompt().augmentUserMessage(enrichedUserText))
                .context(context)
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain advisorChain) {
        Object sources = response.context().get(RETRIEVED_SOURCES);
        if (sources == null) {
            return response;
        }

        ChatResponse.Builder chatResponseBuilder = response.chatResponse() == null
                ? ChatResponse.builder()
                : ChatResponse.builder().from(response.chatResponse());
        chatResponseBuilder.metadata(RETRIEVED_SOURCES, sources);
        return ChatClientResponse.builder()
                .chatResponse(chatResponseBuilder.build())
                .context(response.context())
                .build();
    }

    private List<TranscriptSearchService.TranscriptSource> findSources(List<Document> documents, String query) {
        List<TranscriptSearchService.TranscriptSource> sources = new ArrayList<>();
        Set<String> seenSlugs = new LinkedHashSet<>();
        for (Document document : documents) {
            if (sources.size() == MAX_CASES) {
                break;
            }
            String slug = extractSlug(document);
            if (slug == null || !seenSlugs.add(slug)) {
                continue;
            }
            String title = Objects.toString(document.getMetadata().get("title"), "");
            String transcriptQuery = title + " " + query;
            transcriptSearchService.search(slug, transcriptQuery, SNIPPETS_PER_CASE)
                    .filter(source -> !source.snippets().isEmpty())
                    .ifPresent(sources::add);
        }
        return List.copyOf(sources);
    }

    private String extractSlug(Document document) {
        Object metadataSlug = document.getMetadata().get("slug");
        if (metadataSlug != null) {
            return metadataSlug.toString();
        }
        Matcher matcher = CASE_SLUG_PATTERN.matcher(Objects.toString(document.getText(), ""));
        return matcher.find() ? matcher.group(1) : null;
    }

    private String buildContext(List<TranscriptSearchService.TranscriptSource> sources) {
        String instruction = """

                以下是首层摘要案例对应的逐字稿二级检索片段。它们只用于核验摘要，不代表完整上下文。
                引用具体判断或原话时，必须紧跟 [案例编号 HH:mm:ss-HH:mm:ss]，并优先附定位视频链接。
                自动转录可能有同音字或断句错误；片段不支持的结论不要补写，也不要据此做医学诊断。

                """;
        StringBuilder builder = new StringBuilder(instruction);
        for (TranscriptSearchService.TranscriptSource source : sources) {
            String sourceBlock = source.formatForContext() + "\n\n";
            if (builder.length() + sourceBlock.length() > MAX_CONTEXT_CHARS) {
                break;
            }
            builder.append(sourceBlock);
        }
        return builder.toString().trim();
    }
}
