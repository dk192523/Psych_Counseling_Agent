package com.dk.psychclient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LocalServerEndpointTest {
    @TempDir Path directory;

    @Test void absenceDoesNotCreateEndpointOrDataDirectory() throws IOException {
        LocalServerEndpoint endpoint = LocalServerEndpoint.forDataDirectory(directory.resolve("data"));
        assertTrue(endpoint.read().isEmpty());
        assertEquals(directory.resolve(LocalServerEndpoint.FILE_NAME), endpoint.file());
        assertFalse(Files.exists(directory.resolve("data")));
        assertFalse(Files.exists(endpoint.file()));
    }

    @Test void publishesCompleteVersionedEndpointAndReplacesPreviousInstance() throws IOException {
        LocalServerEndpoint endpoint = LocalServerEndpoint.forDataDirectory(directory.resolve("data"));
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        endpoint.publish(ServerAddress.parse("http://127.0.0.1:41234"), first);
        assertEquals(new LocalServerEndpoint.Endpoint(1, first, ServerAddress.parse("http://127.0.0.1:41234")),
                new LocalServerEndpoint(endpoint.file()).read().orElseThrow());
        endpoint.publish(ServerAddress.parse("http://127.0.0.1:42345"), second);
        assertEquals(second, endpoint.read().orElseThrow().instanceId());
        assertFalse(Files.readString(endpoint.file(), StandardCharsets.UTF_8).contains(first.toString()));
        try (var children = Files.list(directory)) {
            assertEquals(1, children.count(), "Atomic publication cleans up temporary files");
        }
    }

    @Test void oldInstanceCannotRemoveAReplacementEndpoint() throws IOException {
        LocalServerEndpoint endpoint = LocalServerEndpoint.forDataDirectory(directory.resolve("data"));
        UUID old = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        endpoint.publish(ServerAddress.parse("http://127.0.0.1:41234"), old);
        endpoint.publish(ServerAddress.parse("http://127.0.0.1:42345"), current);
        assertFalse(endpoint.removeIfOwned(old));
        assertTrue(Files.exists(endpoint.file()));
        assertTrue(endpoint.removeIfOwned(current));
        assertTrue(endpoint.read().isEmpty());
        assertFalse(endpoint.removeIfOwned(current));
    }

    @Test void rejectsRemoteAddressesAndUntrustedVersionsBeforeConnecting() throws IOException {
        LocalServerEndpoint endpoint = new LocalServerEndpoint(directory.resolve("endpoint.properties"));
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
                () -> endpoint.publish(ServerAddress.parse("https://remote.example"), id));
        assertFalse(Files.exists(endpoint.file()));
        for (String root : new String[] {"https://remote.example/", "http://localhost:43210/",
                "http://127.0.0.1:43210/api", "http://user@127.0.0.1:43210/"}) {
            Files.writeString(endpoint.file(), "protocol.version=1\ninstance.id=" + id + "\nserver.root=" + root + "\n",
                    StandardCharsets.UTF_8);
            assertThrows(IOException.class, endpoint::read, root);
        }
        Files.writeString(endpoint.file(), "protocol.version=2\ninstance.id=" + id
                + "\nserver.root=http://127.0.0.1:43210/\n", StandardCharsets.UTF_8);
        assertThrows(IOException.class, endpoint::read);
    }

    @Test void rejectsMalformedAndOversizedFilesWithoutDeletingThem() throws IOException {
        LocalServerEndpoint endpoint = new LocalServerEndpoint(directory.resolve("endpoint.properties"));
        for (String value : new String[] {"", "protocol.version=1\ninstance.id=1-1-1-1-1\nserver.root=http://127.0.0.1:43210/\n",
                "instance.id=\\uZZZZ\n", "x".repeat(8193)}) {
            Files.writeString(endpoint.file(), value, StandardCharsets.UTF_8);
            assertThrows(IOException.class, endpoint::read);
            assertThrows(IOException.class, () -> endpoint.removeIfOwned(UUID.randomUUID()));
            assertTrue(Files.exists(endpoint.file()));
        }
    }

    @Test void endpointIsRestrictedToThePublishingUser() throws IOException {
        LocalServerEndpoint endpoint = LocalServerEndpoint.forDataDirectory(directory.resolve("data"));
        endpoint.publish(ServerAddress.parse("http://127.0.0.1:43210"), UUID.randomUUID());
        AclFileAttributeView acl = Files.getFileAttributeView(endpoint.file(), AclFileAttributeView.class);
        if (acl != null) {
            var entries = acl.getAcl();
            assertEquals(1, entries.size(), "Only the publishing file owner has an ACL entry");
            var entry = entries.getFirst();
            assertEquals(AclEntryType.ALLOW, entry.type());
            assertEquals(acl.getOwner(), entry.principal());
            assertEquals(EnumSet.allOf(AclEntryPermission.class), entry.permissions());
        } else {
            PosixFileAttributeView posix = Files.getFileAttributeView(endpoint.file(), PosixFileAttributeView.class);
            assertNotNull(posix);
            assertEquals(PosixFilePermissions.fromString("rw-------"), posix.readAttributes().permissions());
        }
        assertTrue(endpoint.read().isPresent(), "The publishing user can read the final endpoint");
        try (var channel = Files.newByteChannel(endpoint.file(), StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            assertTrue(channel.isOpen(), "The publishing user can open the final endpoint for writing");
        }
    }
}
