package com.dk.dkaiagent.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalDesktopDataSourceConfigTest {
    @TempDir Path root;

    @Test
    void defaultsToPsychCounselorUnderLocalAppData() {
        Path localAppData = root.resolve("local-app-data");

        Path resolved = LocalDesktopDataSourceConfig.resolveDataDirectory("", localAppData.toString(),
                root.resolve("home"), root.resolve("working"));

        assertEquals(localAppData.resolve("PsychCounselor"), resolved);
    }

    @Test
    void rejectsRelativeConfiguredDirectory() {
        assertThrows(IllegalArgumentException.class, () -> LocalDesktopDataSourceConfig.resolveDataDirectory(
                "relative-data", root.resolve("local-app-data").toString(),
                root.resolve("home"), root.resolve("working")));
    }

    @Test
    void rejectsWorkingDirectoryAndItsChildren() {
        Path working = root.resolve("working");

        assertThrows(IllegalArgumentException.class, () -> LocalDesktopDataSourceConfig.resolveDataDirectory(
                working.toString(), null, root.resolve("home"), working));
        assertThrows(IllegalArgumentException.class, () -> LocalDesktopDataSourceConfig.resolveDataDirectory(
                working.resolve("data").toString(), null, root.resolve("home"), working));
    }

    @Test
    void encryptsPersistedDataAndReopensWithTheSameLauncherKey() throws Exception {
        String sentinel = "private-counseling-content-" + UUID.randomUUID();
        char[] key = "0123456789abcdef".repeat(4).toCharArray();
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("desktopProcessBoundary",
                Map.of("app.desktop.database-file-password", key)));
        LocalDesktopDataSourceConfig config = new LocalDesktopDataSourceConfig();

        try (HikariDataSource source = (HikariDataSource) config.desktopDataSource(root, environment)) {
            try (Connection connection = source.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE sensitive_notes (id INTEGER PRIMARY KEY, content VARCHAR(200))");
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO sensitive_notes (id, content) VALUES (?, ?)")) {
                    insert.setInt(1, 1);
                    insert.setString(2, sentinel);
                    assertEquals(1, insert.executeUpdate());
                }
            }
        }

        byte[] databaseBytes = Files.readAllBytes(root.resolve("counseling.mv.db"));
        assertTrue(databaseBytes.length > 0);
        // ISO-8859-1 maps every byte directly, so this detects the ASCII sentinel in the raw file.
        assertFalse(new String(databaseBytes, StandardCharsets.ISO_8859_1).contains(sentinel),
                "Sensitive content must not be stored as plaintext in the H2 database file");

        try (HikariDataSource reopened = (HikariDataSource) config.desktopDataSource(root, environment);
             Connection connection = reopened.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT content FROM sensitive_notes WHERE id = 1")) {
            assertTrue(result.next());
            assertEquals(sentinel, result.getString("content"));
            assertFalse(result.next());
        }
    }

    @Test
    void rejectsEncryptionKeysFromSourcesOtherThanTheLauncherBoundary() {
        for (String sourceName : new String[]{"applicationConfig: [classpath:/application-local-desktop.yml]",
                "systemProperties", "systemEnvironment"}) {
            StandardEnvironment environment = new StandardEnvironment();
            environment.getPropertySources().addFirst(new MapPropertySource(sourceName,
                    Map.of("app.desktop.database-file-password", "0123456789abcdef".repeat(4).toCharArray())));

            assertThrows(IllegalStateException.class,
                    () -> new LocalDesktopDataSourceConfig().desktopDataSource(root, environment), sourceName);
        }
    }
}
