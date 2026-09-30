package com.dk.dkaiagent.exam;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ExamServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExamService service = new ExamService(mapper);

    @Test
    void loadsUtf8OriginalBankAndReturnsOnlyRequestedCount() {
        var bank = service.questions(50);
        assertTrue(bank.size() >= 5 && bank.size() <= 8);
        assertEquals(2, service.questions(2).size());
        assertTrue(bank.stream().allMatch(question -> question.source().contains("本地原创模拟练习")));
        assertTrue(bank.getFirst().text().contains("模拟"));
        assertFalse(ExamService.SEED_RESOURCE.startsWith("document/"));
        assertEquals("exam/practice/seed.jsonl", ExamService.SEED_RESOURCE);
        assertThrows(UnsupportedOperationException.class, () -> bank.getFirst().options().put("A", "changed"));
    }

    @Test
    void publicProjectionContainsOnlyFiveContractFields() {
        var json = mapper.valueToTree(service.questions(1).getFirst());
        assertEquals(5, json.size());
        for (String field : new String[]{"id", "text", "options", "domain", "source"}) {
            assertTrue(json.has(field));
        }
        assertFalse(json.has("answer"));
        assertFalse(json.has("explanation"));
        assertFalse(json.has("sourceUrl"));
    }

    @Test
    void gradesBothOutcomesWithoutChangingQuestionBank() {
        var before = service.questions(50);
        var correct = service.answer("practice-001", "B");
        var incorrect = service.answer("practice-001", "A");
        assertTrue(correct.correct());
        assertFalse(incorrect.correct());
        assertEquals("B", incorrect.correctOption());
        assertFalse(incorrect.explanation().isBlank());
        assertEquals("https://www.cci.health.wa.gov.au/Resources/Looking-after-yourself/anxiety", incorrect.sourceUrl());
        assertEquals(before, service.questions(50));
    }

    @Test
    void rejectsBadLimitsIdsAndOptionsAndDistinguishesUnknownQuestions() {
        for (int limit : new int[]{-1, 0, 51, Integer.MAX_VALUE}) {
            assertStatus(HttpStatus.BAD_REQUEST, () -> service.questions(limit));
        }
        for (String id : new String[]{null, "", "../seed", "Practice-001", "x".repeat(65)}) {
            assertStatus(HttpStatus.BAD_REQUEST, () -> service.answer(id, "A"));
        }
        for (String option : new String[]{null, "", "a", " A", "AA", "E"}) {
            assertStatus(HttpStatus.BAD_REQUEST, () -> service.answer("practice-001", option));
        }
        assertStatus(HttpStatus.NOT_FOUND, () -> service.answer("practice-unknown", "A"));
    }

    @Test
    void invalidAndDuplicateSeedsFailClosed() throws Exception {
        assertThrows(IllegalStateException.class, () -> fromSeed(""));
        assertThrows(IllegalStateException.class, () -> fromSeed("{broken}"));
        assertThrows(IllegalStateException.class, () -> fromSeed("{}"));
        var seed = new ExamQuestion("test-001", "题目", java.util.Map.of("A", "甲", "B", "乙"),
                "测试", "本地原创模拟练习", "A", "解析", "classpath:exam/practice/test");
        String line = mapper.writeValueAsString(seed);
        assertThrows(IllegalStateException.class, () -> fromSeed(line + "\n" + line));
        assertStatus(HttpStatus.BAD_REQUEST, () -> fromSeed(line).answer("test-001", "C"));
    }

    private ExamService fromSeed(String content) {
        return new ExamService(mapper, new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertStatus(HttpStatus status, org.junit.jupiter.api.function.Executable action) {
        assertEquals(status, assertThrows(ResponseStatusException.class, action).getStatusCode());
    }
}
