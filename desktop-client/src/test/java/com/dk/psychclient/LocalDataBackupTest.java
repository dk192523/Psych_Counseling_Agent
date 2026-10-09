package com.dk.psychclient;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.EnumSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class LocalDataBackupTest {
    @TempDir Path directory;

    @BeforeEach void protectTestRoot() throws IOException {
        // JUnit's temporary directory may inherit sandbox/group ACEs; fixtures must use the production allowlist.
        LocalDataBackup.secureOwnedDirectory(directory);
    }

    private static final LocalDataBackup.KeyProtector TEST_PROTECTOR = new LocalDataBackup.KeyProtector() {
        @Override public byte[] protect(byte[] key) { return key.clone(); }
        @Override public byte[] unprotect(byte[] wrapped) { return wrapped.clone(); }
    };

    private Path fixtureData() throws IOException {
        Path data = createOwnedDataDirectory("application");
        byte[] bytes = new byte[220_000];
        new SecureRandom().nextBytes(bytes);
        Files.write(data.resolve("counseling.mv.db"), bytes);
        Files.writeString(data.getParent().resolve("secrets.dpapi"), "fake-secret-for-format-tests", StandardCharsets.UTF_8);
        return data;
    }

    private Path createOwnedDataDirectory(String name) throws IOException {
        Path application = Files.createDirectory(directory.resolve(name));
        LocalDataBackup.secureOwnedDirectory(application);
        Path data = Files.createDirectory(application.resolve("data"));
        LocalDataBackup.secureOwnedDirectory(data);
        return data;
    }

    @FunctionalInterface interface FixtureVerifier { void verify(Path restored) throws IOException; }

    private LocalDataBackup formatBackup(Path data, FixtureVerifier verifier) {
        return new LocalDataBackup(data, TEST_PROTECTOR, (restored, cancelled) -> verifier.verify(restored),
                (temporary, destination) -> Files.move(temporary, destination));
    }

    @Test void streamsMultipleFramesIntoFreshIsolationAndNeverTouchesProduction() throws IOException {
        Path data = fixtureData();
        Files.writeString(data.resolve("diagnostic.log"), "must never be archived", StandardCharsets.UTF_8);
        byte[] originalDatabase = Files.readAllBytes(data.resolve("counseling.mv.db"));
        byte[] originalSecret = Files.readAllBytes(data.getParent().resolve("secrets.dpapi"));
        AtomicBoolean verified = new AtomicBoolean();
        LocalDataBackup backup = formatBackup(data, restored -> {
            assertTrue(restored.startsWith(data.getParent().resolve("restore-drills")));
            assertNotEquals(data, restored);
            assertArrayEquals(originalDatabase, Files.readAllBytes(restored.resolve("counseling.mv.db")));
            assertArrayEquals(originalSecret, Files.readAllBytes(restored.resolve("secrets.dpapi")));
            verified.set(true);
        });
        Path archive = backup.create(directory.resolve("saved.psychbackup"));
        LocalDataBackup.DrillResult first = backup.restoreDrill(archive);
        LocalDataBackup.DrillResult second = backup.restoreDrill(archive);
        assertTrue(verified.get());
        assertNotEquals(first.dataDirectory(), second.dataDirectory());
        assertEquals(first.backupCreatedAt(), second.backupCreatedAt());
        try (var files = Files.list(first.dataDirectory())) {
            assertEquals(Set.of("counseling.mv.db", "secrets.dpapi", "manifest.properties"),
                    files.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
        }
        assertArrayEquals(originalDatabase, Files.readAllBytes(data.resolve("counseling.mv.db")));
        assertArrayEquals(originalSecret, Files.readAllBytes(data.getParent().resolve("secrets.dpapi")));
        assertFalse(Files.readString(first.dataDirectory().resolve("manifest.properties"), StandardCharsets.UTF_8)
                .contains("fake-secret-for-format-tests"));
        try (var files = Files.list(directory)) {
            assertFalse(files.anyMatch(file -> file.getFileName().toString().endsWith(".partial")));
        }
    }

    @Test void refusesExistingDestinationDataNestedDirectoriesAndLiveDatabaseLocks() throws IOException {
        Path data = fixtureData();
        LocalDataBackup backup = formatBackup(data, ignored -> { });
        Path existing = directory.resolve("existing.psychbackup");
        byte[] sentinel = "keep existing backup".getBytes(StandardCharsets.UTF_8);
        Files.write(existing, sentinel);
        assertThrows(IOException.class, () -> backup.create(existing));
        assertArrayEquals(sentinel, Files.readAllBytes(existing));
        assertThrows(IOException.class, () -> backup.create(data.resolve("inside.psychbackup")));
        Path nested = Files.createDirectory(data.resolve("unexpected-directory"));
        assertThrows(IOException.class, () -> backup.create(directory.resolve("nested.psychbackup")));
        Files.delete(nested);
        try (FileChannel channel = FileChannel.open(data.resolve("counseling.mv.db"), StandardOpenOption.READ, StandardOpenOption.WRITE);
             var held = channel.lock()) {
            assertThrows(IOException.class, () -> backup.create(directory.resolve("locked.psychbackup")));
            assertFalse(Files.exists(directory.resolve("locked.psychbackup")));
        }
    }

    @Test void rejectsTamperingInHeaderCiphertextTruncationAndBytesAfterFinalMarker() throws IOException {
        Path data = fixtureData();
        AtomicBoolean verified = new AtomicBoolean();
        LocalDataBackup backup = formatBackup(data, ignored -> verified.set(true));
        Path archive = backup.create(directory.resolve("valid.psychbackup"));
        byte[] original = Files.readAllBytes(archive);
        for (int offset : List.of(12, 24, 100, original.length - 1)) {
            byte[] tampered = original.clone();
            tampered[offset] ^= 1;
            Path file = directory.resolve("tampered-" + offset + ".psychbackup");
            Files.write(file, tampered);
            assertThrows(IOException.class, () -> backup.restoreDrill(file));
        }
        Path truncated = directory.resolve("truncated.psychbackup");
        Files.write(truncated, Arrays.copyOf(original, original.length - 20));
        assertThrows(IOException.class, () -> backup.restoreDrill(truncated));
        Path appended = directory.resolve("appended.psychbackup");
        Files.write(appended, Arrays.copyOf(original, original.length + 1));
        assertThrows(IOException.class, () -> backup.restoreDrill(appended));
        assertFalse(verified.get(), "Only an entirely authenticated container may reach the database verifier");
    }

    @Test void rejectsAuthenticatedZipTraversalUnknownDuplicateAndOversizedEntries() throws Exception {
        Path data = fixtureData();
        LocalDataBackup backup = formatBackup(data, ignored -> fail("Invalid ZIP must not reach the database verifier"));
        for (String badName : List.of("../outside.txt", "data/counseling.mv.db", "C:\\outside.txt", "unknown.dpapi")) {
            Path file = directory.resolve("invalid-" + Math.abs(badName.hashCode()) + ".psychbackup");
            Files.write(file, seal(zip(List.of("counseling.mv.db", "secrets.dpapi", "manifest.properties", badName), 1)));
            assertThrows(IOException.class, () -> backup.restoreDrill(file));
        }
        byte[] duplicateZip = zip(List.of("counseling.mv.db", "secrets.dpapi", "manifest.properties", "unknown.dpapi"), 1);
        replaceSameLength(duplicateZip, "unknown.dpapi", "secrets.dpapi");
        Path duplicates = directory.resolve("duplicate.psychbackup");
        Files.write(duplicates, seal(duplicateZip));
        assertThrows(IOException.class, () -> backup.restoreDrill(duplicates));
        Path tooLarge = directory.resolve("oversized.psychbackup");
        Files.write(tooLarge, seal(zip(List.of("counseling.mv.db", "secrets.dpapi", "manifest.properties"), 16 * 1024 + 1)));
        assertThrows(IOException.class, () -> backup.restoreDrill(tooLarge));
        assertFalse(Files.exists(data.getParent().resolve("outside.txt")));
        assertFalse(Files.exists(directory.resolve("outside.txt")));
    }

    @Test void failedDrillDoesNotOverwriteProductionOrRetainAcceptedCopies() throws IOException {
        Path data = fixtureData();
        byte[] original = Files.readAllBytes(data.resolve("counseling.mv.db"));
        LocalDataBackup backup = formatBackup(data, ignored -> { throw new IOException("Wrong credential fixture"); });
        Path archive = backup.create(directory.resolve("saved.psychbackup"));
        assertThrows(IOException.class, () -> backup.restoreDrill(archive));
        assertArrayEquals(original, Files.readAllBytes(data.resolve("counseling.mv.db")));
        try (var sessions = Files.list(data.getParent().resolve("restore-drills"))) {
            assertEquals(0, sessions.count());
        }
    }

    @Test void cancellationDuringVerifierReturnRemovesTheUnacceptedCopy() throws IOException {
        Path data = fixtureData();
        AtomicBoolean cancelled = new AtomicBoolean();
        LocalDataBackup backup = new LocalDataBackup(data, TEST_PROTECTOR, (restored, signal) -> {
            assertFalse(signal.getAsBoolean());
            cancelled.set(true);
            assertTrue(signal.getAsBoolean());
        }, (temporary, destination) -> Files.move(temporary, destination));
        // Creation authenticates the archive without invoking the H2 verifier.
        Path archive = backup.create(directory.resolve("cancelled.psychbackup"));
        assertThrows(CancellationException.class, () -> backup.restoreDrill(archive, cancelled::get));
        try (var sessions = Files.list(data.getParent().resolve("restore-drills"))) {
            assertEquals(0, sessions.count());
        }
    }

    @Test void boundedVerifierInterruptsBlockedWorkOnCancellationAndTimeout() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread cancel = new Thread(() -> {
            try { if (entered.await(2, TimeUnit.SECONDS)) cancelled.set(true); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        });
        cancel.start();
        try {
            assertThrows(CancellationException.class, () -> LocalDataBackup.runBoundedVerification(() -> {
                entered.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException failure) { interrupted.set(true); Thread.currentThread().interrupt(); }
            }, cancelled::get, Duration.ofSeconds(2)));
            assertTrue(interrupted.get());
        } finally { cancel.join(2500); }
        assertThrows(IOException.class, () -> LocalDataBackup.runBoundedVerification(() -> {
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        }, () -> false, Duration.ofMillis(100)));
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void acceptsTrustedInheritanceButRejectsSharedDestinationBeforeWriting() throws Exception {
        Path data = fixtureData();
        Path destination = Files.createDirectory(directory.resolve("private-destination"));
        AclFileAttributeView acl = Files.getFileAttributeView(destination, AclFileAttributeView.class);
        var lookup = destination.getFileSystem().getUserPrincipalLookupService();
        var current = lookup.lookupPrincipalByName(com.sun.jna.platform.win32.Secur32Util.getUserNameEx(2));
        var system = lookup.lookupPrincipalByName(com.sun.jna.platform.win32.Advapi32Util.getAccountBySid("S-1-5-18").fqn);
        var administrators = lookup.lookupPrincipalByName(com.sun.jna.platform.win32.Advapi32Util.getAccountBySid("S-1-5-32-544").fqn);
        List<AclEntry> trusted = List.of(current, system, administrators).stream().map(principal ->
                AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(principal)
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()).toList();
        acl.setAcl(trusted);
        LocalDataBackup.requirePrivatePath(destination, true, false);
        LocalDataBackup backup = formatBackup(data, ignored -> { });
        Path saved = backup.create(destination.resolve("private.psychbackup"));
        LocalDataBackup.requirePrivatePath(saved, false, true);
        var everyone = lookup.lookupPrincipalByName(com.sun.jna.platform.win32.Advapi32Util.getAccountBySid("S-1-1-0").fqn);
        var shared = new java.util.ArrayList<>(trusted);
        shared.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(everyone)
                .setPermissions(AclEntryPermission.READ_DATA).build());
        acl.setAcl(shared);
        Path refused = destination.resolve("shared.psychbackup");
        assertThrows(IOException.class, () -> backup.create(refused));
        assertFalse(Files.exists(refused));
        try (var files = Files.list(destination)) {
            assertEquals(List.of("private.psychbackup"), files.map(file -> file.getFileName().toString()).toList());
        } finally { acl.setAcl(trusted); }
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void dpapiAndEncryptedH2DrillUsesOnlyCopiedExistingCredentials() throws Exception {
        Path data = createOwnedDataDirectory("native-application");
        DpapiSecretStore store = new DpapiSecretStore(data);
        try (DpapiSecretStore.Material material = store.loadOrCreate()) {
            createH2Fixture(data, material.databasePassword);
            byte[] original = Files.readAllBytes(data.resolve("counseling.mv.db"));
            byte[] originalSecret = Files.readAllBytes(data.getParent().resolve("secrets.dpapi"));
            LocalDataBackup backup = new LocalDataBackup(data);
            Path archive = backup.create(directory.resolve("native.psychbackup"));
            LocalDataBackup.DrillResult result = backup.restoreDrill(archive);
            try (DpapiSecretStore.Material copied = DpapiSecretStore.loadExistingFile(result.dataDirectory().resolve("secrets.dpapi"))) {
                assertArrayEquals(material.databasePassword, copied.databasePassword);
            }
            assertArrayEquals(original, Files.readAllBytes(data.resolve("counseling.mv.db")));
            assertArrayEquals(originalSecret, Files.readAllBytes(data.getParent().resolve("secrets.dpapi")));
            assertArrayEquals(original, Files.readAllBytes(result.dataDirectory().resolve("counseling.mv.db")));
        }
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void missingCredentialsNeverCreateReplacementKeyOrDirectories() {
        Path data = directory.resolve("missing/data");
        assertThrows(IOException.class, () -> new DpapiSecretStore(data).loadExisting());
        assertThrows(IOException.class, () -> DpapiSecretStore.loadExistingFile(directory.resolve("missing-secrets.dpapi")));
        assertFalse(Files.exists(data.getParent()));
        assertFalse(Files.exists(directory.resolve("missing-secrets.dpapi")));
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void h2CannotBeVerifiedWithAReplacementSecret() throws Exception {
        Path data = createOwnedDataDirectory("wrong-key");
        DpapiSecretStore store = new DpapiSecretStore(data);
        try (DpapiSecretStore.Material original = store.loadOrCreate()) {
            createH2Fixture(data, original.databasePassword);
            char[] replacement = "b".repeat(64).toCharArray();
            try (DpapiSecretStore.Material wrong = new DpapiSecretStore.Material(replacement, new char[0], new char[0])) {
                store.save(wrong);
            }
            byte[] databaseBefore = Files.readAllBytes(data.resolve("counseling.mv.db"));
            byte[] secretsBefore = Files.readAllBytes(data.getParent().resolve("secrets.dpapi"));
            LocalDataBackup backup = new LocalDataBackup(data);
            Path archive = backup.create(directory.resolve("wrong-key.psychbackup"));
            assertThrows(IOException.class, () -> backup.restoreDrill(archive));
            assertArrayEquals(databaseBefore, Files.readAllBytes(data.resolve("counseling.mv.db")));
            assertArrayEquals(secretsBefore, Files.readAllBytes(data.getParent().resolve("secrets.dpapi")));
        }
    }

    private static void createH2Fixture(Path data, char[] filePassword) throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        char[] password = Arrays.copyOf(filePassword, filePassword.length + 1);
        password[filePassword.length] = ' ';
        source.setURL("jdbc:h2:file:" + data.resolve("counseling").toString().replace('\\', '/')
                + ";CIPHER=AES;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
        source.setUser("sa");
        source.setPasswordChars(password);
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            for (String table : List.of("psych_user", "psych_conversation", "psych_chat_message", "psych_conversation_memory",
                    "psych_conversation_tombstone", "psych_chat_turn", "local_desktop_vector_store")) {
                statement.execute("CREATE TABLE " + table + " (id BIGINT PRIMARY KEY)");
                statement.execute("INSERT INTO " + table + " VALUES (1)");
            }
        } finally {
            source.setPasswordChars(new char[0]);
            Arrays.fill(password, '\0');
        }
    }

    private static byte[] zip(List<String> names, int manifestLength) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (String name : names) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(new byte[name.equals("manifest.properties") ? manifestLength : 1]);
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /** Independent small-fixture encoder for authenticated malicious ZIPs, not a production API. */
    private static byte[] seal(byte[] payload) throws Exception {
        assertTrue(payload.length <= 65536);
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        byte[] prefix = new byte[8];
        new SecureRandom().nextBytes(prefix);
        byte[] header = ByteBuffer.allocate(56).putInt(0x50534231).putInt(1).putInt(65536)
                .put(prefix).putInt(32).put(key).array();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(header);
            for (int counter = 0; counter < 2; counter++) {
                byte[] plain = counter == 0 ? payload : new byte[0];
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128,
                        ByteBuffer.allocate(12).put(prefix).putInt(counter).array()));
                cipher.updateAAD(header);
                cipher.updateAAD(ByteBuffer.allocate(8).putInt(counter).putInt(plain.length).array());
                output.writeInt(plain.length);
                output.write(cipher.doFinal(plain));
            }
        } finally { Arrays.fill(key, (byte) 0); }
        return bytes.toByteArray();
    }

    private static void replaceSameLength(byte[] bytes, String from, String to) {
        byte[] source = from.getBytes(StandardCharsets.UTF_8);
        byte[] target = to.getBytes(StandardCharsets.UTF_8);
        assertEquals(source.length, target.length);
        for (int offset = 0; offset <= bytes.length - source.length; offset++) {
            boolean match = true;
            for (int index = 0; index < source.length; index++) {
                if (bytes[offset + index] != source[index]) { match = false; break; }
            }
            if (match) System.arraycopy(target, 0, bytes, offset, target.length);
        }
    }
}
