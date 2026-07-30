package com.dk.dkaiagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_INTEGRATION_TESTS", matches = "true")
class DkAiAgentApplicationTests {

    @Test
    void contextLoads() {
    }

}
