package com.dk.dkaiagent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class KnowledgeBaseLinkageTest {

    private static final Pattern SLUG_PATTERN = Pattern.compile(
            "案例编号 (\\d{4}-\\d{2}-\\d{2}-call-\\d{2})"
    );

    @Test
    void everyIndexedCaseHasASlugInTheRuntimeKnowledgeBase() throws IOException {
        Path casesPath = Path.of("../counseling-kb/cases.json").toAbsolutePath().normalize();
        Path documentDirectory = Path.of("src/main/resources/document").toAbsolutePath().normalize();
        assumeTrue(Files.isRegularFile(casesPath));
        assumeTrue(Files.isDirectory(documentDirectory));

        JsonNode cases = new ObjectMapper().readTree(casesPath.toFile()).path("cases");
        Set<String> indexedSlugs = new HashSet<>();
        cases.forEach(caseNode -> indexedSlugs.add(caseNode.path("slug").asText()));

        Set<String> documentSlugs = new HashSet<>();
        try (var paths = Files.list(documentDirectory)) {
            for (Path path : paths.filter(file -> file.getFileName().toString().endsWith(".md")).toList()) {
                Matcher matcher = SLUG_PATTERN.matcher(Files.readString(path));
                while (matcher.find()) {
                    documentSlugs.add(matcher.group(1));
                }
            }
        }

        assertEquals(810, indexedSlugs.size());
        assertEquals(indexedSlugs, documentSlugs);
    }
}
