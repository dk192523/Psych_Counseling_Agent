package com.dk.dkaiagent.controller;

import com.dk.dkaiagent.exam.ExamQuestion;
import com.dk.dkaiagent.exam.ExamService;
import com.dk.dkaiagent.security.CurrentUser;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/exam/questions")
public class ExamController {
    private final ExamService examService;

    public ExamController(ExamService examService) {
        this.examService = examService;
    }

    @GetMapping
    public List<ExamQuestion.QuestionView> questions(@RequestParam(name = "limit", required = false) String limit) {
        CurrentUser.requireUserId();
        if (limit != null && !limit.matches("[1-9][0-9]?")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit 须为 1 至 50 的整数");
        }
        return examService.questions(limit == null ? 5 : Integer.parseInt(limit));
    }

    @PostMapping("/{id}/answer")
    public ExamQuestion.AnswerResult answer(@PathVariable("id") String id, @RequestBody JsonNode request) {
        CurrentUser.requireUserId();
        // Read the JSON type explicitly so numbers/arrays cannot be coerced to an option string.
        if (request == null || !request.isObject() || request.size() != 1
                || !request.has("option") || !request.get("option").isTextual()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体须为仅含字符串 option 的 JSON 对象");
        }
        return examService.answer(id, request.get("option").textValue());
    }
}
