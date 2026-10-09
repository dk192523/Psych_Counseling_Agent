package com.dk.dkaiagent.controller;

import com.dk.dkaiagent.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SystemHealthController.class, properties = {
        "app.system.server-version=2.0.0-test",
        "app.system.protocol-version=2",
        "app.docs.enabled=false"
})
@Import(SecurityConfig.class)
class SystemHealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void publicHealthReturnsOnlyConfiguredCompatibilityFields() throws Exception {
        mockMvc.perform(get("/api/system/health").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.serverVersion").value("2.0.0-test"))
                .andExpect(jsonPath("$.protocolVersion").value("2"))
                .andExpect(jsonPath("$.*", hasSize(3)));
    }

    @Test
    void neighboringSystemEndpointsRemainProtected() throws Exception {
        mockMvc.perform(get("/api/system/config").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }
}
