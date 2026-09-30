package com.dk.dkaiagent.exam;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Stateless local practice: no conversation, persistence, model or retrieval dependencies. */
@Service
public class ExamService {
    public static final String SEED_RESOURCE = "exam/practice/seed.jsonl";
    public static final int MAX_LIMIT = 50;
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private static final Pattern OPTION = Pattern.compile("[A-D]");

    private final Map<String, ExamQuestion> questions;

    @Autowired
    public ExamService(ObjectMapper objectMapper) {
        this(objectMapper, new ClassPathResource(SEED_RESOURCE));
    }

    ExamService(ObjectMapper objectMapper, Resource resource) {
        Map<String, ExamQuestion> loaded = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                ExamQuestion question = objectMapper.readValue(line, ExamQuestion.class);
                if (!validSeed(question) || loaded.putIfAbsent(question.id(), question) != null) {
                    throw new IllegalStateException("Invalid or duplicate practice question at line " + lineNumber);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load local practice question bank", exception);
        }
        if (loaded.isEmpty()) {
            throw new IllegalStateException("Local practice question bank is empty");
        }
        questions = Collections.unmodifiableMap(loaded);
    }

    /** Stable seed order; limits larger than the bank return all available questions. */
    public List<ExamQuestion.QuestionView> questions(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw badRequest("limit 须为 1 至 " + MAX_LIMIT + " 的整数");
        }
        return questions.values().stream().limit(limit).map(ExamQuestion::toView).toList();
    }

    public ExamQuestion.AnswerResult answer(String id, String option) {
        if (id == null || !ID.matcher(id).matches()) {
            throw badRequest("题目 id 格式不正确");
        }
        if (option == null || !OPTION.matcher(option).matches()) {
            throw badRequest("option 须为 A、B、C 或 D");
        }
        ExamQuestion question = questions.get(id);
        if (question == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "题目不存在");
        }
        if (!question.options().containsKey(option)) {
            throw badRequest("所选选项不存在");
        }
        return new ExamQuestion.AnswerResult(question.answer().equals(option), question.answer(),
                question.explanation(), question.sourceUrl());
    }

    private static boolean validSeed(ExamQuestion question) {
        return question != null && question.id() != null && ID.matcher(question.id()).matches()
                && nonBlank(question.text()) && nonBlank(question.domain()) && nonBlank(question.source())
                && nonBlank(question.explanation()) && nonBlank(question.sourceUrl())
                && question.options().size() >= 2 && question.options().size() <= 4
                && question.options().entrySet().stream().allMatch(entry -> entry.getKey() != null
                    && OPTION.matcher(entry.getKey()).matches() && nonBlank(entry.getValue()))
                && question.answer() != null && question.options().containsKey(question.answer());
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
