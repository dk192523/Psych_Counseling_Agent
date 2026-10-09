package com.dk.psychclient;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/** Discovery only: never contains credentials and never permits a non-loopback connection. */
public final class LocalServerEndpoint {
    public static final int PROTOCOL_VERSION = 1;
    public static final String FILE_NAME = "server-endpoint.properties";
    private static final long MAX_FILE_BYTES = 8192;
    private final Path file;

    public record Endpoint(int protocolVersion, UUID instanceId, ServerAddress address) {
        public Endpoint {
            if (protocolVersion != PROTOCOL_VERSION) {
                throw new IllegalArgumentException("本地服务协议版本不兼容，请更新客户端与服务端。");
            }
            Objects.requireNonNull(instanceId, "instanceId");
            requireLoopback(address);
        }
    }

    public static final class ProtocolMismatchException extends IOException {
        private ProtocolMismatchException() { super("本地服务协议版本不兼容，请更新客户端与服务端。"); }
    }

    public LocalServerEndpoint(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    public static LocalServerEndpoint forCurrentUser() {
        return forDataDirectory(ClientSettings.forCurrentUser().dataDirectory());
    }

    public static LocalServerEndpoint forDataDirectory(Path directory) {
        return new LocalServerEndpoint(directory.toAbsolutePath().normalize().getParent().resolve(FILE_NAME));
    }

    public Path file() { return file; }

    public Optional<Endpoint> read() throws IOException {
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_FILE_BYTES) {
            throw new IOException("本地服务端点文件无效。");
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
            int version = Integer.parseInt(properties.getProperty("protocol.version"));
            if (version != PROTOCOL_VERSION) throw new ProtocolMismatchException();
            String instance = properties.getProperty("instance.id");
            UUID id = UUID.fromString(instance);
            if (!id.toString().equals(instance)) throw new IllegalArgumentException("Invalid instance ID");
            return Optional.of(new Endpoint(version,
                    id, ServerAddress.parse(properties.getProperty("server.root"))));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new IOException("本地服务端点格式无效或协议不兼容。", invalid);
        }
    }

    /** Call only after readiness, while holding LocalBackendHost's user-level process lock. */
    public void publish(ServerAddress address, UUID instanceId) throws IOException {
        Endpoint endpoint = new Endpoint(PROTOCOL_VERSION, instanceId, address);
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "server-endpoint-", ".tmp");
        try {
            Properties properties = new Properties();
            properties.setProperty("protocol.version", Integer.toString(endpoint.protocolVersion()));
            properties.setProperty("instance.id", endpoint.instanceId().toString());
            properties.setProperty("server.root", endpoint.address().toString());
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                properties.store(writer, "PsychCounselor local server endpoint (UTF-8)");
            }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            // Discovery contains no secrets. Finish writing before tightening Windows access;
            // the restricted ACL survives the atomic rename into the published endpoint.
            protectForCurrentUser(temporary);
            // No non-atomic fallback: readers must see either the old complete endpoint or the new one.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** The host lock is kept until this returns, so another server cannot publish during deletion. */
    public boolean removeIfOwned(UUID instanceId) throws IOException {
        Objects.requireNonNull(instanceId, "instanceId");
        Optional<Endpoint> endpoint = read();
        return endpoint.isPresent() && endpoint.get().instanceId().equals(instanceId) && Files.deleteIfExists(file);
    }

    private static void requireLoopback(ServerAddress address) {
        Objects.requireNonNull(address, "address");
        if (!"http".equals(address.root().getScheme()) || !"127.0.0.1".equals(address.root().getHost())) {
            throw new IllegalArgumentException("本地服务端点必须是 127.0.0.1 的 HTTP 根地址。");
        }
    }

    private static void protectForCurrentUser(Path temporary) throws IOException {
        AclFileAttributeView acl = Files.getFileAttributeView(temporary, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (acl != null) {
            // The newly created file's owner identifies the publishing Windows account by SID,
            // avoiding ambiguous local/domain lookups of the short user.name value.
            var user = acl.getOwner();
            acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(user)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
            return;
        }
        PosixFileAttributeView posix = Files.getFileAttributeView(temporary, PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
            return;
        }
        throw new IOException("当前文件系统无法保护用户级本地服务端点。");
    }
}
