package com.dk.dkaiagent.tools;

import com.dk.dkaiagent.rag.TranscriptSearchService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Lets the model verify a retrieved case summary against timestamped raw text.
 */
@Component
public class TranscriptLookupTool {

    private static final int TOOL_SNIPPET_COUNT = 3;

    private final TranscriptSearchService transcriptSearchService;

    public TranscriptLookupTool(TranscriptSearchService transcriptSearchService) {
        this.transcriptSearchService = transcriptSearchService;
    }

    @Tool(description = "按案例编号检索公开连麦逐字稿中的相关原文。引用摘要案例的具体判断或原话前必须先调用本工具核验；返回内容包含案例标题、时间戳和视频来源。")
    public String lookupTranscript(
            @ToolParam(description = "摘要案例中的案例编号，例如 2026-07-18-call-07") String slug,
            @ToolParam(description = "需要在该案例原文中核验的问题、观点或关键词，不能为空") String query) {
        try {
            return transcriptSearchService.search(slug, query, TOOL_SNIPPET_COUNT)
                    .map(source -> source.snippets().isEmpty()
                            ? "找到了案例，但逐字稿中没有与查询足够相关的片段。请不要引用未核验的原话。"
                            : source.formatForContext())
                    .orElse("未找到该案例的逐字稿。该案例可能属于网站侧缺失的 11 份原文之一，请只引用摘要并明确说明未核验原文。");
        } catch (IllegalArgumentException e) {
            return "逐字稿查询参数无效：" + e.getMessage();
        }
    }
}
