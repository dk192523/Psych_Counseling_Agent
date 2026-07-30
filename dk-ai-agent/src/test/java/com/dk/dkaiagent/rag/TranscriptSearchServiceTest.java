package com.dk.dkaiagent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscriptSearchServiceTest {

    private static final String SLUG = "2026-07-18-call-07";

    @TempDir
    Path transcriptDirectory;

    private ObjectMapper objectMapper;
    private TranscriptSearchService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new TranscriptSearchService(objectMapper, transcriptDirectory);
    }

    @Test
    void returnsEmptyWhenTranscriptIsMissing() {
        assertTrue(service.search(SLUG, "洁癖和减肥", 2).isEmpty());
    }

    @Test
    void rejectsBlankQueryAndPathTraversalSlug() {
        assertThrows(IllegalArgumentException.class, () -> service.search(SLUG, "  ", 2));
        assertThrows(IllegalArgumentException.class,
                () -> service.search("../../application", "洁癖", 2));
    }

    @Test
    void ranksTheMostRelevantExcerptFirstAndIncludesProvenance() throws IOException {
        writeTranscript(List.of(
                cue(1, "00:00:01", "00:00:03", "晚上好，先说说你的基本情况"),
                cue(2, "00:00:04", "00:00:06", "我担心女儿最近不太开心"),
                cue(3, "00:00:07", "00:00:09", "她偶尔会控制饮食"),
                cue(4, "00:00:10", "00:00:12", "我们先把时间轴排清楚"),
                cue(5, "00:00:13", "00:00:15", "父母不要急着替孩子做决定"),
                cue(6, "00:00:16", "00:00:18", "先观察她的日常状态"),
                cue(7, "00:00:19", "00:00:21", "也要尊重房间边界"),
                cue(8, "00:00:22", "00:00:24", "如果功能受损就及时就医"),
                cue(9, "00:00:25", "00:00:27", "这部分先聊到这里"),
                cue(10, "00:00:28", "00:00:30", "下面换一个问题"),
                cue(11, "00:00:31", "00:00:33", "她有洁癖并且反复洗手"),
                cue(12, "00:00:34", "00:00:36", "八十多斤还执着减肥"),
                cue(13, "00:00:37", "00:00:39", "父亲问到底要不要介入"),
                cue(14, "00:00:40", "00:00:42", "核心要看健康和基本功能有没有受损"),
                cue(15, "00:00:43", "00:00:45", "抓大放小，不要强化她的洁癖"),
                cue(16, "00:00:46", "00:00:48", "减肥这件事也要结合医生意见"),
                cue(17, "00:00:49", "00:00:51", "不要把关心变成高压控制"),
                cue(18, "00:00:52", "00:00:54", "先把孩子稳稳接住"),
                cue(19, "00:00:55", "00:00:57", "再谈父亲自己的职业问题"),
                cue(20, "00:00:58", "00:01:00", "摄影可以从本地活动开始")
        ));

        TranscriptSearchService.TranscriptSource source = service
                .search(SLUG, "女儿洁癖减肥，父亲要不要介入，重点看健康", 3)
                .orElseThrow();

        assertEquals("父亲求助女儿洁癖减肥", source.title());
        assertFalse(source.snippets().isEmpty());
        assertTrue(source.snippets().getFirst().text().contains("洁癖"));
        assertTrue(source.snippets().getFirst().text().contains("减肥"));
        assertTrue(source.snippets().getFirst().text().contains("介入"));
        assertTrue(source.snippets().getFirst().sourceUrl().startsWith("https://example.com/video?t="));
        assertTrue(source.formatForContext().contains("案例编号：" + SLUG));
        assertTrue(source.formatForContext().contains("[" + SLUG + " "));
    }

    @Test
    void capsSnippetCountAndTextLength() throws IOException {
        List<Map<String, Object>> cues = new ArrayList<>();
        for (int index = 1; index <= 45; index++) {
            String text = index % 15 == 0
                    ? "焦虑".repeat(300) + "需要关注睡眠和进食功能"
                    : "普通对话内容" + index;
            cues.add(cue(index, timestamp(index), timestamp(index + 1), text));
        }
        writeTranscript(cues);

        TranscriptSearchService.TranscriptSource source = service
                .search(SLUG, "焦虑 睡眠 进食", 99)
                .orElseThrow();

        assertTrue(source.snippets().size() <= 3);
        assertTrue(source.snippets().stream().allMatch(snippet -> snippet.text().length() <= 420));
    }

    private void writeTranscript(List<Map<String, Object>> cues) throws IOException {
        Map<String, Object> transcript = new LinkedHashMap<>();
        transcript.put("slug", SLUG);
        transcript.put("title", "父亲求助女儿洁癖减肥");
        transcript.put("url", "https://example.com/video");
        transcript.put("cues", cues);
        objectMapper.writeValue(transcriptDirectory.resolve(SLUG + ".json").toFile(), transcript);
    }

    private Map<String, Object> cue(int number, String start, String end, String text) {
        return Map.of(
                "number", number,
                "start", start,
                "end", end,
                "text", text
        );
    }

    private String timestamp(int seconds) {
        int minute = seconds / 60;
        int second = seconds % 60;
        return "00:%02d:%02d".formatted(minute, second);
    }
}
