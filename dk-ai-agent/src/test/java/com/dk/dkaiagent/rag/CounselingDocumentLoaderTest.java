package com.dk.dkaiagent.rag;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_INTEGRATION_TESTS", matches = "true")
class CounselingDocumentLoaderTest {

    @Resource
    private CounselingDocumentLoader counselingDocumentLoader;

    @Test
    void loadMarkdowns() {
        counselingDocumentLoader.loadMarkdowns();
    }
}
