package com.dk.dkaiagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class ApplicationYamlTest {

    @Test
    void applicationYamlContainsTranscriptDirectoryWithoutDuplicateKeys() throws IOException {
        List<PropertySource<?>> propertySources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));

        Object transcriptDirectory = propertySources.stream()
                .map(source -> source.getProperty("app.rag.transcript-directory"))
                .filter(value -> value != null)
                .findFirst()
                .orElse(null);
        assertNotNull(transcriptDirectory);

        Object deepThinkingEnabled = propertySources.stream()
                .map(source -> source.getProperty("app.deep-thinking.enabled"))
                .filter(value -> value != null)
                .findFirst()
                .orElse(null);
        assertNotNull(deepThinkingEnabled);
    }
}
