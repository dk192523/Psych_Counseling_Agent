package com.dk.dkaiagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekWebSearchToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

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
