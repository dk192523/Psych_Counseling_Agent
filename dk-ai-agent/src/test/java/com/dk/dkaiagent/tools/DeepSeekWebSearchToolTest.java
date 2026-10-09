package com.dk.dkaiagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekWebSearchToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void unconfiguredDesktopSearchNeverTouchesHttpClient() {
        var tool = new DeepSeekWebSearchTool("sk-desktop-unconfigured", "https://api.deepseek.com",
                "deepseek-chat", "web_search_20250305");
        var http = org.mockito.Mockito.mock(org.springframework.web.client.RestClient.class);
        org.springframework.test.util.ReflectionTestUtils.setField(tool, "restClient", http);
        var environment = new org.springframework.mock.env.MockEnvironment();
        environment.setActiveProfiles("local-desktop");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(tool, "configureDesktopLlm",
                new com.dk.dkaiagent.app.DesktopLlmAvailability(environment));

        org.junit.jupiter.api.Assertions.assertEquals(
                com.dk.dkaiagent.app.DesktopLlmAvailability.UNCONFIGURED_MESSAGE,
                tool.searchWeb("今天有哪些心理健康政策更新？"));
        org.mockito.Mockito.verifyNoInteractions(http);
    }

    @Test
    void formatsAnswerAndDeduplicatedSources() throws Exception {
        JsonNode response = objectMapper.readTree("""
                {
                  "content": [
                    {"type": "text", "text": "已核验的答案。"},
                    {
                      "type": "web_search_tool_result",
                      "content": [
                        {"type": "web_search_result", "title": "来源甲", "url": "https://example.com/a"},
                        {"type": "web_search_result", "title": "重复来源", "url": "https://example.com/a"},
                        {"type": "web_search_result", "title": "来源乙", "url": "https://example.com/b"}
                      ]
                    }
                  ]
                }
                """);

        String result = DeepSeekWebSearchTool.formatResponse(response);

        assertTrue(result.contains("已核验的答案。"));
        assertTrue(result.contains("来源甲: https://example.com/a"));
        assertTrue(result.contains("来源乙: https://example.com/b"));
        assertTrue(result.indexOf("https://example.com/a") == result.lastIndexOf("https://example.com/a"));
    }
}
