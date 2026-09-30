package com.dk.dkaiagent.controller;

import com.dk.dkaiagent.exam.ExamService;
import com.dk.dkaiagent.security.SecurityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ExamControllerTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ExamController(new ExamService(new ObjectMapper()))).build();
        var principal = new SecurityConfig.PsychUserPrincipal(7L, "student", "USER");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void bothEndpointsRequireCurrentUserEvenWithoutFilterChain() throws Exception {
        SecurityContextHolder.clearContext();
        mvc.perform(get("/exam/questions")).andExpect(status().isUnauthorized());
        mvc.perform(post("/exam/questions/practice-001/answer")
                .contentType(MediaType.APPLICATION_JSON).content("{\"option\":\"B\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listsOnlyPublicFieldsWithDefaultAndExplicitLimit() throws Exception {
        mvc.perform(get("/exam/questions")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(5)));
        mvc.perform(get("/exam/questions").param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0]", aMapWithSize(5)))
                .andExpect(jsonPath("$[0].id").value("practice-001"))
                .andExpect(jsonPath("$[0].text").isString())
                .andExpect(jsonPath("$[0].options").isMap())
                .andExpect(jsonPath("$[0].domain").isString())
                .andExpect(jsonPath("$[0].source").isString())
                .andExpect(jsonPath("$[0].answer").doesNotExist())
                .andExpect(jsonPath("$[0].correctOption").doesNotExist())
                .andExpect(jsonPath("$[0].explanation").doesNotExist());
    }

    @Test
    void returnsExactAnswerContractForCorrectAndIncorrectOptions() throws Exception {
        for (String option : new String[]{"A", "B"}) {
            mvc.perform(post("/exam/questions/practice-001/answer")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"option\":\"" + option + "\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$", aMapWithSize(4)))
                    .andExpect(jsonPath("$.correct").value(option.equals("B")))
                    .andExpect(jsonPath("$.correctOption").value("B"))
                    .andExpect(jsonPath("$.explanation").isString())
                    .andExpect(jsonPath("$.sourceUrl").value("https://www.cci.health.wa.gov.au/Resources/Looking-after-yourself/anxiety"));
        }
    }

    @Test
    void malformedLimitsReturn400() throws Exception {
        for (String limit : new String[]{"", "0", "-1", "51", "1.5", "abc", "999999999999999999999"}) {
            mvc.perform(get("/exam/questions").param("limit", limit)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void malformedBodiesAndOptionsReturn400() throws Exception {
        for (String body : new String[]{"", "{", "{}", "null", "[]", "{\"option\":null}",
                "{\"option\":1}", "{\"option\":[]}", "{\"option\":\"\"}", "{\"option\":\"b\"}",
                "{\"option\":\"E\"}", "{\"option\":\"B\",\"extra\":true}"}) {
            mvc.perform(post("/exam/questions/practice-001/answer")
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void malformedIdIs400AndUnknownIdIs404() throws Exception {
        mvc.perform(post("/exam/questions/INVALID/answer")
                .contentType(MediaType.APPLICATION_JSON).content("{\"option\":\"A\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/exam/questions/practice-unknown/answer")
                .contentType(MediaType.APPLICATION_JSON).content("{\"option\":\"A\"}"))
                .andExpect(status().isNotFound());
    }
}
