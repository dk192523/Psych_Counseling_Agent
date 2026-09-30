package com.dk.dkaiagent.exam;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Internal seed record. Only QuestionView is exposed before answering. */
public record ExamQuestion(String id, String text, Map<String, String> options,
                           String domain, String source, String answer,
                           String explanation, String sourceUrl) {
    public ExamQuestion {
        options = options == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(options));
    }

    public QuestionView toView() {
        return new QuestionView(id, text, options, domain, source);
    }

    public record QuestionView(String id, String text, Map<String, String> options,
                               String domain, String source) {
    }

    public record AnswerResult(boolean correct, String correctOption,
                               String explanation, String sourceUrl) {
    }
}
