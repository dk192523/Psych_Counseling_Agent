package com.dk.psychclient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class ClientSettingsTest {
    @TempDir
    Path directory;

    @Test
    void firstRunHasNoImplicitDomainAndDoesNotWriteAFile() throws IOException {
        Path file = directory.resolve("PsychCounselor/client.properties");
        ClientSettings settings = new ClientSettings(file);
        assertTrue(settings.load().isEmpty());
        assertFalse(Files.exists(file));
    }

    @Test
    void savesUtf8PropertiesInUserDirectoryAndReloadsFromNewInstance() throws IOException {
        Path file = directory.resolve("中文目录/PsychCounselor/client.properties");
        ServerAddress address = ServerAddress.parse("https://counseling.example:8443");
        new ClientSettings(file).save(address);

        assertEquals(address, new ClientSettings(file).load().orElseThrow());
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("连接设置"));
        Properties persisted = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            persisted.load(reader);
        }
        assertEquals(2, persisted.size());
        assertEquals("remote", persisted.getProperty("connection.mode"));
        assertEquals(address.toString(), persisted.getProperty("server.root"));
        try (var children = Files.list(file.getParent())) {
            assertEquals(1, children.count(), "Temporary files must be removed after saving");
        }
    }

    @Test
    void changingServerReplacesThePreviousAddress() throws IOException {
        ClientSettings settings = new ClientSettings(directory.resolve("client.properties"));
        settings.save(ServerAddress.parse("https://first.example"));
        ServerAddress replacement = ServerAddress.parse("https://second.example:8443");
        settings.save(replacement);
        assertEquals(replacement, settings.load().orElseThrow());
        assertFalse(Files.readString(settings.file()).contains("first.example"));
    }

    @Test
    void manuallyEditedFileCannotBypassRemoteHttpsPolicy() throws IOException {
        Path file = directory.resolve("client.properties");
        Files.writeString(file, "# 手动修改\nserver.root=http://remote.example\n", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> new ClientSettings(file).load());
    }

    @Test
    void missingRootAndMalformedPropertiesRequireRepair() throws IOException {
        Path file = directory.resolve("client.properties");
        ClientSettings settings = new ClientSettings(file);
        Files.writeString(file, "# 空配置\n", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, settings::load);
        Files.writeString(file, "server.root=\\uZZZZ\n", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, settings::load);
        settings.save(ServerAddress.parse("https://counseling.example"));
        assertTrue(settings.load().isPresent());
    }

    @Test
    void legacyDevServerConfigurationUsesLocalMode() throws IOException {
        Path file = directory.resolve("client.properties");
        for (String root : new String[] {"http://127.0.0.1:3001/", "http://127.0.0.1:3001",
                "http://localhost:3001/", "HTTP://LOCALHOST:3001"}) {
            Files.writeString(file, "server.root=" + root + "\n", StandardCharsets.UTF_8);
            ClientSettings.Connection connection = new ClientSettings(file).loadConnection();
            assertEquals(ClientSettings.Mode.LOCAL, connection.mode(), root);
            assertNull(connection.remote(), root);
        }
    }

    @Test
    void legacyRemoteAndOtherLoopbackAddressesKeepRemoteMode() throws IOException {
        Path file = directory.resolve("client.properties");
        for (String root : new String[] {"https://counseling.example:8443/",
                "http://127.0.0.1:8080/", "http://localhost:8080/", "https://localhost:3001/"}) {
            Files.writeString(file, "server.root=" + root + "\n", StandardCharsets.UTF_8);
            ClientSettings.Connection connection = new ClientSettings(file).loadConnection();
            assertEquals(ClientSettings.Mode.REMOTE, connection.mode(), root);
            assertEquals(ServerAddress.parse(root), connection.remote(), root);
        }
    }

    @Test
    void explicitRemoteModeKeepsDevServerAddress() throws IOException {
        Path file = directory.resolve("client.properties");
        Files.writeString(file, "connection.mode=remote\nserver.root=http://127.0.0.1:3001/\n",
                StandardCharsets.UTF_8);
        ClientSettings.Connection connection = new ClientSettings(file).loadConnection();
        assertEquals(ClientSettings.Mode.REMOTE, connection.mode());
        assertEquals(ServerAddress.parse("http://127.0.0.1:3001/"), connection.remote());
    }

    @Test
    void directoryAtConfigurationPathIsReportedAsIoFailure() throws IOException {
        Path file = Files.createDirectory(directory.resolve("client.properties"));
        ClientSettings settings = new ClientSettings(file);
        assertThrows(IOException.class, settings::load);
        assertThrows(IOException.class, () -> settings.save(ServerAddress.parse("https://counseling.example")));
        assertTrue(Files.isDirectory(file));
        try (var children = Files.list(directory)) {
            assertEquals(1, children.count(), "Failed replacement must clean up its temporary file");
        }
    }
}
