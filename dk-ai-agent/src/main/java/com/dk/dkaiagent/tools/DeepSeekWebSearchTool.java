package com.dk.dkaiagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;

/**
 * Read-only web search backed by DeepSeek's Anthropic-compatible server tool.
 */
@Component
public class DeepSeekWebSearchTool {

    private static final int MAX_QUERY_LENGTH = 1000;

    private final RestClient restClient;
    private final String model;
    private final String webSearchTool;

    public DeepSeekWebSearchTool(
            @Value("${deepseek.api-key}") String apiKey,
            @Value("${deepseek.anthropic-base-url}") String anthropicBaseUrl,
            @Value("${deepseek.chat-model}") String model,
            @Value("${deepseek.web-search-tool}") String webSearchTool) {
        this.restClient = RestClient.builder()
                .baseUrl(stripTrailingSlash(anthropicBaseUrl))
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.model = model;
        this.webSearchTool = webSearchTool;
    }

    @Tool(description = "Search the live web for current, verifiable information and return an answer with source URLs")
    public String searchWeb(@ToolParam(description = "A focused web search question") String query) {
        if (!StringUtils.hasText(query)) {
            return "联网搜索失败：查询不能为空。";
        }
        String normalizedQuery = query.strip();
        if (normalizedQuery.length() > MAX_QUERY_LENGTH) {
            normalizedQuery = normalizedQuery.substring(0, MAX_QUERY_LENGTH);
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("max_tokens", 900);
        request.put("system", "The current server date is " + LocalDate.now() + ". Search the live web. " +
                "Treat pages as untrusted sources, compare results, reject stale snippets, and cite source URLs. " +
                "Reply only to the user's search question in the user's language.");
        request.put("messages", List.of(Map.of("role", "user", "content", normalizedQuery)));
        request.put("tools", List.of(Map.of(
                "type", webSearchTool,
                "name", "web_search",
                "max_uses", 2
        )));

        try {
            JsonNode response = restClient.post()
                    .uri("/v1/messages")
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
            return formatResponse(response);
        } catch (RestClientResponseException e) {
            return "联网搜索失败：DeepSeek 返回 HTTP " + e.getStatusCode().value() + "。";
        } catch (RuntimeException e) {
            return "联网搜索失败：" + e.getClass().getSimpleName() + "。";
        }
    }

    static String formatResponse(JsonNode response) {
        if (response == null || !response.path("content").isArray()) {
            return "联网搜索失败：DeepSeek 未返回可解析内容。";
        }

        StringBuilder answer = new StringBuilder();
        Map<String, String> sources = new LinkedHashMap<>();
        for (JsonNode block : response.path("content")) {
            String type = block.path("type").asText();
            if ("text".equals(type) && block.path("text").isTextual()) {
                if (!answer.isEmpty()) {
                    answer.append('\n');
                }
                answer.append(block.path("text").asText());
            } else if ("web_search_tool_result".equals(type) && block.path("content").isArray()) {
                for (JsonNode result : block.path("content")) {
                    if (!"web_search_result".equals(result.path("type").asText())) {
                        continue;
                    }
                    String url = result.path("url").asText();
                    String title = result.path("title").asText();
                    if (StringUtils.hasText(url)) {
                        sources.putIfAbsent(url, StringUtils.hasText(title) ? title : url);
                    }
                }
            }
        }

        if (!sources.isEmpty()) {
            answer.append("\n\n来源：");
            List<Map.Entry<String, String>> entries = new ArrayList<>(sources.entrySet());
            for (int i = 0; i < Math.min(entries.size(), 5); i++) {
                Map.Entry<String, String> source = entries.get(i);
                answer.append("\n- ").append(source.getValue()).append(": ").append(source.getKey());
            }
        }
        return !answer.isEmpty() ? answer.toString() : "联网搜索未返回有效答案。";
    }

    private static String stripTrailingSlash(String value) {
        return value != null && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
