package com.dk.psychclient;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Properties;

/** Connection preferences only. Local credentials are kept in the separate DPAPI store. */
public final class ClientSettings {
    private static final String SERVER_ROOT = "server.root";
    private static final String MODE = "connection.mode";
    public enum Mode { LOCAL, REMOTE }
    public record Connection(Mode mode, ServerAddress remote) {
        public Connection {
            if (mode == null || (mode == Mode.REMOTE && remote == null)) {
                throw new IllegalArgumentException("远程模式必须填写服务端地址。");
            }
        }
        public static Connection local() { return new Connection(Mode.LOCAL, null); }
    }
    private final Path file;

    public ClientSettings(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    public static ClientSettings forCurrentUser() {
        return forCurrentUser(System.getenv("LOCALAPPDATA"), Path.of(System.getProperty("user.home")));
    }

    static ClientSettings forCurrentUser(String localAppData, Path userHome) {
        Path localRoot = localAppData == null || localAppData.isBlank()
                ? userHome.resolve("AppData").resolve("Local") : Path.of(localAppData);
        return new ClientSettings(localRoot.resolve("PsychCounselor").resolve("client.properties"));
    }

    public Path file() {
        return file;
    }

    public Path dataDirectory() {
        return file.getParent().resolve("data");
    }

    public Connection loadConnection() throws IOException {
        if (Files.notExists(file)) return Connection.local();
        Properties properties = readProperties();
        String mode = properties.getProperty(MODE);
        // Older clients saved just server.root, including the retired local dev server.
        if (mode == null) {
            ServerAddress legacy = load().orElseThrow();
            if (legacy.toString().equals("http://127.0.0.1:3001/")
                    || legacy.toString().equals("http://localhost:3001/")) return Connection.local();
            return new Connection(Mode.REMOTE, legacy);
        }
        if (mode.equals("local")) return Connection.local();
        if (mode.equals("remote")) return new Connection(Mode.REMOTE,
                ServerAddress.parse(properties.getProperty(SERVER_ROOT)));
        throw new IllegalArgumentException("连接模式无效，请重新设置。");
    }

    private Properties readProperties() throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    public Optional<ServerAddress> load() throws IOException {
        if (Files.notExists(file)) return Optional.empty();
        Properties properties = readProperties();
        String root = properties.getProperty(SERVER_ROOT);
        if ("local".equals(properties.getProperty(MODE))) return Optional.empty();
        if (root == null || root.isBlank()) {
            throw new IllegalArgumentException("连接配置缺少服务端根地址，请重新设置。");
        }
        // Validate every read as well, so manually edited files cannot bypass the URL policy.
        return Optional.of(ServerAddress.parse(root));
    }

    public void save(ServerAddress address) throws IOException {
        saveConnection(new Connection(Mode.REMOTE, address));
    }

    public void saveConnection(Connection connection) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "client-", ".tmp");
        try {
            Properties properties = new Properties();
            properties.setProperty(MODE, connection.mode() == Mode.LOCAL ? "local" : "remote");
            if (connection.remote() != null) properties.setProperty(SERVER_ROOT, connection.remote().toString());
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                writer.write("#PsychCounselor 连接设置（UTF-8）\n");
                properties.store(writer, null);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
