package com.dk.psychclient;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.AccCtrl;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Secur32Util;
import com.sun.jna.platform.win32.WinCrypt;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinReg;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Same-machine, same-Windows-user cold backups. No production restore API exists. */
final class LocalDataBackup {
    static final String EXTENSION = ".psychbackup";
    static final String DATABASE_ENTRY = "counseling.mv.db";
    static final String SECRETS_ENTRY = "secrets.dpapi";
    static final String MANIFEST_ENTRY = "manifest.properties";
    static final long MAX_DATABASE_BYTES = 8L * 1024 * 1024 * 1024;
    private static final int MAX_SECRETS_BYTES = 128 * 1024;
    private static final int MAX_MANIFEST_BYTES = 16 * 1024;
    private static final long MAX_ZIP_BYTES = MAX_DATABASE_BYTES + MAX_SECRETS_BYTES + MAX_MANIFEST_BYTES + 16L * 1024 * 1024;
    private static final long MAX_CONTAINER_BYTES = MAX_ZIP_BYTES + (MAX_ZIP_BYTES / 65536 + 2) * 20 + 65536;
    private static final Set<String> ENTRIES = Set.of(DATABASE_ENTRY, SECRETS_ENTRY, MANIFEST_ENTRY);
    private static final BooleanSupplier NEVER_CANCELLED = () -> false;
    private final Path dataDirectory;
    private final Path applicationDirectory;
    private final Path drillRoot;
    private final KeyProtector protector;
    private final DatabaseVerifier verifier;
    private final AtomicPublisher publisher;

    record DrillResult(Path dataDirectory, Instant backupCreatedAt) { }

    interface KeyProtector {
        byte[] protect(byte[] key) throws IOException;
        byte[] unprotect(byte[] wrapped) throws IOException;
    }
    @FunctionalInterface interface DatabaseVerifier {
        void verify(Path restoredData, BooleanSupplier cancelled) throws IOException;
    }
    @FunctionalInterface interface AtomicPublisher { void publish(Path temporary, Path destination) throws IOException; }

    LocalDataBackup(Path dataDirectory) {
        this(dataDirectory, new WindowsKeyProtector(), LocalDataBackup::verifyH2, LocalDataBackup::publishOnWindows);
    }

    // Narrow test seams keep format/limit tests independent of Spring, native DPAPI and JavaFX.
    LocalDataBackup(Path dataDirectory, KeyProtector protector, DatabaseVerifier verifier, AtomicPublisher publisher) {
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
        this.applicationDirectory = Objects.requireNonNull(this.dataDirectory.getParent());
        this.drillRoot = applicationDirectory.resolve("restore-drills");
        this.protector = Objects.requireNonNull(protector);
        this.verifier = Objects.requireNonNull(verifier);
        this.publisher = Objects.requireNonNull(publisher);
    }

    Path create(Path destination) throws IOException { return create(destination, NEVER_CANCELLED); }

    /** Caller must stop Spring successfully while retaining desktop.lock for this entire operation. */
    Path create(Path destination, BooleanSupplier cancelled) throws IOException {
        checkCancelled(cancelled);
        requireDirectory(dataDirectory);
        // Reject directory junctions, links and unexpected nested/special data, never follow them.
        try (var children = Files.list(dataDirectory)) {
            var iterator = children.iterator();
            while (iterator.hasNext()) requireRegularFile(iterator.next(), Long.MAX_VALUE);
        }
        Path database = dataDirectory.resolve(DATABASE_ENTRY);
        Path secrets = applicationDirectory.resolve(SECRETS_ENTRY);
        requireRegularFile(database, MAX_DATABASE_BYTES);
        requireRegularFile(secrets, MAX_SECRETS_BYTES);
        Path target = destination.toAbsolutePath().normalize();
        if (target.startsWith(applicationDirectory) || target.getFileName() == null) {
            throw new IOException("请把备份保存到本地服务数据目录以外的独立目录。");
        }
        requireDirectory(target.getParent());
        requireLocalVolume(target.getParent());
        requirePrivateDirectory(target.getParent());
        requireAbsent(target);
        Path temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".partial");
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        try {
            // Spring close can suppress bean-destruction errors. An exclusive file lock also proves H2 released the file.
            try (FileChannel databaseChannel = FileChannel.open(database,
                    Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS));
                 FileLock databaseLock = databaseChannel.tryLock()) {
                if (databaseLock == null) throw new IOException("数据库仍被占用，已取消备份；请等待服务完全停止。");
                try (FileChannel output = createPrivateFile(temporary)) {
                    try (FramedOutput encrypted = new FramedOutput(Channels.newOutputStream(output), key, protector);
                         ZipOutputStream archive = new ZipOutputStream(encrypted, StandardCharsets.UTF_8)) {
                        // H2 and DPAPI are already encrypted, so avoid expensive futile compression.
                        archive.setLevel(Deflater.NO_COMPRESSION);
                        FileDigest databaseDigest = writeEntry(archive, DATABASE_ENTRY,
                                Channels.newInputStream(databaseChannel), MAX_DATABASE_BYTES, cancelled);
                        FileDigest secretsDigest;
                        try (InputStream input = Files.newInputStream(secrets, LinkOption.NOFOLLOW_LINKS)) {
                            secretsDigest = writeEntry(archive, SECRETS_ENTRY, input, MAX_SECRETS_BYTES, cancelled);
                        }
                        byte[] manifest = new Manifest(Instant.now(), databaseDigest, secretsDigest).encode();
                        writeEntry(archive, MANIFEST_ENTRY, new ByteArrayInputStream(manifest), MAX_MANIFEST_BYTES, cancelled);
                    }
                    output.force(true);
                }
                checkCancelled(cancelled);
                // Authenticate the written container and all archive hashes before exposing a final filename.
                inspect(temporary, false, cancelled);
                requireDirectory(target.getParent());
                requirePrivateDirectory(target.getParent());
                requireRegularFile(temporary, MAX_CONTAINER_BYTES);
                requirePrivatePath(temporary, false, true);
                requireAbsent(target);
                checkCancelled(cancelled);
                publisher.publish(temporary, target);
            } catch (OverlappingFileLockException inUse) {
                throw new IOException("数据库仍被占用，已取消备份；请等待服务完全停止。");
            }
            return target;
        } finally {
            Arrays.fill(key, (byte) 0);
            // Only the fresh UUID .partial is removed. Existing final files are never removed or replaced.
            deleteOwnedFile(temporary);
        }
    }

    DrillResult restoreDrill(Path backup) throws IOException { return restoreDrill(backup, NEVER_CANCELLED); }

    DrillResult restoreDrill(Path backup, BooleanSupplier cancelled) throws IOException {
        return inspect(backup.toAbsolutePath().normalize(), true, cancelled);
    }

    private DrillResult inspect(Path backup, boolean retainRestoredData, BooleanSupplier cancelled) throws IOException {
        checkCancelled(cancelled);
        requireRegularFile(backup, MAX_CONTAINER_BYTES);
        Path restoredData = createDrillDataDirectory();
        Path payload = restoredData.resolve(".payload.zip.partial");
        boolean complete = false;
        try {
            // Authenticate every bounded GCM frame, the final marker and EOF before interpreting ZIP metadata.
            try (InputStream input = Files.newInputStream(backup, LinkOption.NOFOLLOW_LINKS);
                 FramedInput decrypted = new FramedInput(input, protector);
                 FileChannel output = createPrivateFile(payload)) {
                copy(decrypted, Channels.newOutputStream(output), MAX_ZIP_BYTES, cancelled);
                output.force(true);
            }
            Manifest manifest = inspectArchive(payload, retainRestoredData ? restoredData : null, cancelled);
            if (retainRestoredData) {
                checkCancelled(cancelled);
                verifier.verify(restoredData, cancelled);
                checkCancelled(cancelled);
            }
            checkCancelled(cancelled);
            complete = true;
            return new DrillResult(restoredData, manifest.createdAt());
        } finally {
            deleteOwnedFile(payload);
            if (!complete || !retainRestoredData) deleteDrill(restoredData);
        }
    }

    private Manifest inspectArchive(Path payload, Path restoredData, BooleanSupplier cancelled) throws IOException {
        try (ZipFile archive = new ZipFile(payload.toFile(), StandardCharsets.UTF_8)) {
            Map<String, ZipEntry> entries = new HashMap<>();
            var enumeration = archive.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                if (entry.isDirectory() || !ENTRIES.contains(entry.getName())
                        || entries.putIfAbsent(entry.getName(), entry) != null
                        || entry.getSize() < 1 || entry.getSize() > entryLimit(entry.getName())
                        || (entry.getMethod() != ZipEntry.STORED && entry.getMethod() != ZipEntry.DEFLATED)) {
                    throw new IOException("备份 ZIP 包含路径、重复、未知、空或超限条目。");
                }
            }
            if (!entries.keySet().equals(ENTRIES)) throw new IOException("备份缺少数据库、凭据或版本清单。");
            byte[] manifestBytes;
            try (InputStream input = archive.getInputStream(entries.get(MANIFEST_ENTRY))) {
                manifestBytes = readBounded(input, MAX_MANIFEST_BYTES, cancelled);
            }
            Manifest manifest = Manifest.decode(manifestBytes);
            for (String name : List.of(DATABASE_ENTRY, SECRETS_ENTRY)) {
                checkCancelled(cancelled);
                FileDigest expected = name.equals(DATABASE_ENTRY) ? manifest.database() : manifest.secrets();
                ZipEntry entry = entries.get(name);
                if (entry.getSize() != expected.bytes()) throw new IOException("备份条目长度与清单不一致。");
                try (InputStream input = archive.getInputStream(entry)) {
                    FileDigest actual;
                    if (restoredData == null) actual = copy(input, OutputStream.nullOutputStream(), expected.bytes(), cancelled);
                    else {
                        try (FileChannel output = createPrivateFile(restoredData.resolve(name))) {
                            actual = copy(input, Channels.newOutputStream(output), expected.bytes(), cancelled);
                            output.force(true);
                        }
                    }
                    if (!expected.matches(actual)) throw new IOException("备份条目校验失败，文件可能已损坏或被修改。");
                }
            }
            if (restoredData != null) {
                try (FileChannel output = createPrivateFile(restoredData.resolve(MANIFEST_ENTRY))) {
                    writeFully(output, ByteBuffer.wrap(manifestBytes));
                    output.force(true);
                }
            }
            return manifest;
        }
    }

    private Path createDrillDataDirectory() throws IOException {
        requireDirectory(applicationDirectory);
        requireLocalVolume(applicationDirectory);
        requirePrivateDirectory(applicationDirectory);
        if (Files.exists(drillRoot, LinkOption.NOFOLLOW_LINKS)) {
            requireDirectory(drillRoot);
            requirePrivateDirectory(drillRoot);
        } else createPrivateDirectory(drillRoot);
        Path session = drillRoot.resolve(UUID.randomUUID().toString());
        requireAbsent(session);
        createPrivateDirectory(session);
        Path restoredData = session.resolve("data");
        try {
            createPrivateDirectory(restoredData);
            return restoredData;
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(session);
            throw failure;
        }
    }

    private void deleteDrill(Path restoredData) throws IOException {
        // No recursive delete: only our fixed files under the fresh session's data directory are touched.
        Path session = restoredData.getParent();
        if (!session.getParent().equals(drillRoot) || !restoredData.getFileName().toString().equals("data")) {
            throw new IOException("隔离验证目录边界无效。");
        }
        requireDirectory(restoredData);
        for (String name : ENTRIES) deleteOwnedFile(restoredData.resolve(name));
        Files.deleteIfExists(restoredData);
        requireDirectory(session);
        Files.deleteIfExists(session);
    }

    private static void verifyH2(Path restoredData, BooleanSupplier cancelled) throws IOException {
        // Driver login/close do not reliably honor JDBC timeouts. Bound the caller's entire wait too.
        runBoundedVerification(() -> verifyH2OnWorker(restoredData, cancelled), cancelled, Duration.ofSeconds(60));
    }

    @FunctionalInterface interface VerificationWork { void run() throws IOException; }

    static void runBoundedVerification(VerificationWork work, BooleanSupplier cancelled, Duration limit) throws IOException {
        checkCancelled(cancelled);
        var executor = Executors.newSingleThreadExecutor(task -> {
            Thread worker = new Thread(task, "psych-backup-h2-verifier");
            worker.setDaemon(true);
            return worker;
        });
        var result = executor.submit(() -> { work.run(); return null; });
        long deadline = System.nanoTime() + limit.toNanos();
        try {
            while (true) {
                checkCancelled(cancelled);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IOException("隔离数据库验证超时，已取消本次演练。");
                try {
                    result.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
                    checkCancelled(cancelled);
                    return;
                } catch (TimeoutException pending) { /* Recheck cancellation while connecting, querying or closing. */ }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException();
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof CancellationException cancelledWork) throw cancelledWork;
            throw new IOException("隔离数据库验证失败，未接受隔离副本。");
        } finally {
            result.cancel(true);
            executor.shutdownNow();
            // Give H2 a bounded cleanup window. A stuck native/driver operation never reports success;
            // if Windows still holds a file, inspect() cleanup also fails instead of accepting that drill.
            boolean interrupted = Thread.interrupted();
            try { executor.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException stop) { interrupted = true; }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }

    private static void verifyH2OnWorker(Path restoredData, BooleanSupplier cancelled) throws IOException {
        checkCancelled(cancelled);
        requireDirectory(restoredData);
        requireRegularFile(restoredData.resolve(DATABASE_ENTRY), MAX_DATABASE_BYTES);
        String base = restoredData.resolve("counseling").toString().replace('\\', '/');
        if (base.indexOf(';') >= 0 || base.indexOf('\n') >= 0 || base.indexOf('\r') >= 0) {
            throw new IOException("隔离数据库目录含有 H2 不允许的字符。");
        }
        // Explicit existing-file API: a missing/unreadable secret is fatal and can never generate a new key.
        try (DpapiSecretStore.Material material = DpapiSecretStore.loadExistingFile(restoredData.resolve(SECRETS_ENTRY))) {
            char[] connectionPassword = Arrays.copyOf(material.databasePassword, material.databasePassword.length + 1);
            connectionPassword[material.databasePassword.length] = ' ';
            Object embedded = null;
            Class<?> type = null;
            try {
                type = Class.forName("org.h2.jdbcx.JdbcDataSource", true, Thread.currentThread().getContextClassLoader());
                embedded = type.getConstructor().newInstance();
                type.getMethod("setURL", String.class).invoke(embedded, "jdbc:h2:file:" + base
                        + ";CIPHER=AES;IFEXISTS=TRUE;ACCESS_MODE_DATA=r;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
                        + ";DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_ON_EXIT=FALSE;TRACE_LEVEL_FILE=0");
                type.getMethod("setUser", String.class).invoke(embedded, "sa");
                type.getMethod("setPasswordChars", char[].class).invoke(embedded, (Object) connectionPassword);
                checkCancelled(cancelled);
                ((DataSource) embedded).setLoginTimeout(10);
                try (Connection connection = ((DataSource) embedded).getConnection();
                     Statement statement = connection.createStatement()) {
                    checkCancelled(cancelled);
                    connection.setReadOnly(true);
                    statement.setQueryTimeout(10);
                    // Fixed table names only. No Spring startup, migrations, writes, user content or model calls.
                    for (String table : List.of("psych_user", "psych_conversation", "psych_chat_message",
                            "psych_conversation_memory", "psych_conversation_tombstone", "psych_chat_turn",
                            "local_desktop_vector_store")) {
                        checkCancelled(cancelled);
                        try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                            checkCancelled(cancelled);
                            if (!rows.next() || rows.getLong(1) < 0) throw new IOException("隔离数据库表验证失败。");
                        }
                        checkCancelled(cancelled);
                    }
                    checkCancelled(cancelled);
                }
                checkCancelled(cancelled);
            } catch (CancellationException cancelledWork) {
                throw cancelledWork;
            } catch (Exception | LinkageError failure) {
                // JDBC/native exception text may contain configuration. Never attach it to a user-facing failure.
                throw new IOException("隔离副本无法用原凭据只读打开，或缺少当前版本需要的数据表。不会修改正式数据。");
            } finally {
                Arrays.fill(connectionPassword, '\0');
                if (embedded != null && type != null) {
                    try { type.getMethod("setPasswordChars", char[].class).invoke(embedded, (Object) new char[0]); }
                    catch (ReflectiveOperationException ignored) { /* No secrets are surfaced or logged. */ }
                }
            }
        }
    }

    private static void publishOnWindows(Path temporary, Path destination) throws IOException {
        if (!Platform.isWindows()) throw new IOException("本地备份需要 Windows。");
        // Same-directory native rename, WRITE_THROUGH, with neither REPLACE_EXISTING nor COPY_ALLOWED.
        // Files.move(ATOMIC_MOVE) leaves existing-target behavior provider-dependent, so do not use it here.
        if (!Kernel32.INSTANCE.MoveFileEx(temporary.toString(), destination.toString(), new WinDef.DWORD(0x8))) {
            throw new IOException("无法原子新建备份文件；请使用本地磁盘上的新文件名，现有文件不会覆盖。");
        }
    }

    private static FileChannel createPrivateFile(Path path) throws IOException {
        requireDirectory(path.getParent());
        requirePrivateDirectory(path.getParent());
        requireAbsent(path);
        FileChannel output = FileChannel.open(path, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS), privateAttribute(path.getParent(), false));
        try {
            protectCreatedPath(path, false);
            return output;
        } catch (IOException | RuntimeException failure) {
            output.close();
            deleteOwnedFile(path);
            throw failure;
        }
    }

    private static void createPrivateDirectory(Path path) throws IOException {
        requireDirectory(path.getParent());
        requirePrivateDirectory(path.getParent());
        Files.createDirectory(path, privateAttribute(path.getParent(), true));
        try { protectCreatedPath(path, true); }
        catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(path);
            throw failure;
        }
    }

    private static void protectCreatedPath(Path path, boolean directory) throws IOException {
        if (Platform.isWindows()) {
            try {
                requireOwnedPath(path, directory);
                AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (view == null) throw new IOException("当前文件系统不支持 Windows ACL。");
                // Replace inherited entries with a narrow allowlist before protecting the DACL.
                view.setAcl(privateAcl(path, directory));
                var descriptor = Advapi32Util.getFileSecurityDescriptor(path.toFile(), false);
                var dacl = descriptor.getDiscretionaryACL();
                if (dacl == null || Advapi32.INSTANCE.SetNamedSecurityInfo(path.toString(),
                        AccCtrl.SE_OBJECT_TYPE.SE_FILE_OBJECT,
                        WinNT.DACL_SECURITY_INFORMATION | WinNT.PROTECTED_DACL_SECURITY_INFORMATION,
                        null, null, dacl.getPointer(), null) != 0) {
                    throw new IOException("无法保护备份文件的 Windows 权限。");
                }
            } catch (RuntimeException failure) {
                throw new IOException("无法保护备份文件的 Windows 权限。");
            }
        } else {
            Files.setPosixFilePermissions(path,
                    PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        }
        requirePrivatePath(path, directory, true);
    }

    /** Secures an existing application-owned directory and its controlled data files. */
    static void secureApplicationStorage(Path applicationDirectory, Path dataDirectory) throws IOException {
        requireDirectory(applicationDirectory);
        protectCreatedPath(applicationDirectory, true);
        for (String name : List.of("client.properties", "server-endpoint.properties", "desktop.lock",
                "secrets.dpapi", "server-autostart.disabled")) {
            Path file = applicationDirectory.resolve(name);
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                // Lock and marker files may legitimately be empty; this is an ACL check, not payload validation.
                requireRegularFileForCleanup(file);
                protectCreatedPath(file, false);
            }
        }
        if (Files.exists(dataDirectory, LinkOption.NOFOLLOW_LINKS)) {
            requireDirectory(dataDirectory);
            protectCreatedPath(dataDirectory, true);
            try (var files = Files.list(dataDirectory)) {
                for (Path file : files.toList()) {
                    requireRegularFileForCleanup(file);
                    protectCreatedPath(file, false);
                }
            }
        }
    }

    /** Tightens a directory created by JUnit or the desktop bootstrap without trusting inherited ACEs. */
    static void secureOwnedDirectory(Path directory) throws IOException {
        requireDirectory(directory);
        protectCreatedPath(directory, true);
    }

    private static void requireOwnedPath(Path path, boolean directory) throws IOException {
        requireSafeAncestors(path);
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (directory ? !attributes.isDirectory() : !attributes.isRegularFile()) {
            throw new IOException("备份路径类型无效。");
        }
        if (Platform.isWindows()) {
            String currentSid = Advapi32Util.getAccountByName(Secur32Util.getUserNameEx(2)).sidString;
            var descriptor = Advapi32Util.getFileSecurityDescriptor(path.toFile(), false);
            String ownerSid = Advapi32Util.convertSidToStringSid(descriptor.getOwner());
            if (!ownerSid.equals(currentSid)) throw new IOException("备份路径必须由当前 Windows 用户拥有。");
        }
    }

    private static List<AclEntry> privateAcl(Path path, boolean directory) throws IOException {
        var lookup = path.getFileSystem().getUserPrincipalLookupService();
        List<String> names = List.of(Secur32Util.getUserNameEx(2),
                Advapi32Util.getAccountBySid("S-1-5-18").fqn,
                Advapi32Util.getAccountBySid("S-1-5-32-544").fqn);
        var entries = new java.util.ArrayList<AclEntry>();
        for (String name : names) {
            var builder = AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                    .setPrincipal(lookup.lookupPrincipalByName(name))
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if (directory) builder.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
            entries.add(builder.build());
        }
        return List.copyOf(entries);
    }

    private static FileAttribute<?> privateAttribute(Path parent, boolean directory) throws IOException {
        if (Files.getFileAttributeView(parent, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null) {
            if (!Platform.isWindows()) throw new IOException("当前平台无法设置 Windows 用户 ACL。");
            // SAM-compatible domain\\user avoids ambiguous local/domain short usernames.
            var user = parent.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByName(Secur32Util.getUserNameEx(2));
            var builder = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(user)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if (directory) builder.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
            List<AclEntry> entries = List.of(builder.build());
            return new FileAttribute<List<AclEntry>>() {
                @Override public String name() { return "acl:acl"; }
                @Override public List<AclEntry> value() { return entries; }
            };
        }
        // Enables injected format tests on POSIX; the production protector and publisher still require Windows.
        if (parent.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        }
        throw new IOException("当前文件系统不能保护用户备份，请选择支持用户 ACL 的本地磁盘。");
    }

    private static void requirePrivateDirectory(Path directory) throws IOException {
        requirePrivatePath(directory, true, false);
    }

    // Existing user-selected directories may inherit trusted SYSTEM/Administrators ACEs.
    // Newly created files/directories additionally require a protected DACL verified by native readback.
    static void requirePrivatePath(Path path, boolean directory, boolean requireProtected) throws IOException {
        if (Platform.isWindows()) {
            try {
                String currentSid = Advapi32Util.getAccountByName(Secur32Util.getUserNameEx(2)).sidString;
                var descriptor = Advapi32Util.getFileSecurityDescriptor(path.toFile(), false);
                String ownerSid = Advapi32Util.convertSidToStringSid(descriptor.getOwner());
                var dacl = descriptor.getDiscretionaryACL();
                if (!trustedBackupSid(ownerSid, currentSid) || dacl == null
                        || (descriptor.Control & WinNT.SE_DACL_PRESENT) == 0
                        || (requireProtected && (descriptor.Control & WinNT.SE_DACL_PROTECTED) == 0)) {
                    throw new IOException("备份目录或文件的 Windows 权限不受保护，请选择当前用户的私有目录。");
                }
                boolean currentUserAllowed = false;
                for (WinNT.ACE_HEADER header : dacl.getACEs()) {
                    if (!(header instanceof WinNT.ACCESS_ACEStructure ace)
                            || (header.AceType != WinNT.ACCESS_ALLOWED_ACE_TYPE
                            && header.AceType != WinNT.ACCESS_DENIED_ACE_TYPE)
                            || !trustedBackupSid(ace.getSidString(), currentSid)) {
                        throw new IOException("备份目录或文件允许其他账号访问，请选择当前用户的私有目录。");
                    }
                    if (header.AceType == WinNT.ACCESS_ALLOWED_ACE_TYPE && ace.getSidString().equals(currentSid)
                            && ace.Mask != 0 && (header.AceFlags & WinNT.INHERIT_ONLY_ACE) == 0) {
                        currentUserAllowed = true;
                    }
                }
                if (!currentUserAllowed) throw new IOException("备份目录或文件未授予当前用户访问权限。");
            } catch (RuntimeException failure) {
                throw new IOException("无法验证备份目录或文件的 Windows 权限。");
            }
        } else if (!Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
                .equals(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"))) {
            throw new IOException("备份目录或文件权限无效，请选择当前用户的私有目录。");
        }
    }

    private static boolean trustedBackupSid(String sid, String currentSid) {
        return sid.equals(currentSid) || sid.equals("S-1-5-18") || sid.equals("S-1-5-32-544");
    }

    private static void requireLocalVolume(Path directory) throws IOException {
        if (Platform.isWindows()) {
            int type = Kernel32.INSTANCE.GetDriveType(directory.getRoot().toString());
            if (type != 3 && type != 2) throw new IOException("备份和隔离验证需要支持用户 ACL 的本地磁盘，不支持网络盘。");
        }
    }

    private static void requireDirectory(Path directory) throws IOException {
        if (directory == null) throw new IOException("备份目录无效。");
        requireSafeAncestors(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("备份目录不存在或不是普通目录。");
    }

    private static void requireRegularFile(Path file, long limit) throws IOException {
        requireSafeAncestors(file);
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() < 1 || attributes.size() > limit) {
            throw new IOException("备份输入不是普通文件、为空或超过大小限制。");
        }
    }

    private static void requireSafeAncestors(Path file) throws IOException {
        Path normalized = file.toAbsolutePath().normalize();
        Path current = normalized.getRoot();
        for (Path part : normalized) {
            current = current.resolve(part);
            BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()
                    || !current.toRealPath().equals(current)
                    || (Platform.isWindows() && (Kernel32.INSTANCE.GetFileAttributes(current.toString())
                    & WinNT.FILE_ATTRIBUTE_REPARSE_POINT) != 0)) {
                throw new IOException("备份路径含有符号链接、目录联接或特殊文件，已拒绝操作。");
            }
        }
    }

    private static void requireAbsent(Path file) throws IOException {
        if (!Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("目标已存在或不可访问，请选择一个新文件名。");
    }

    private static void deleteOwnedFile(Path file) throws IOException {
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return;
        requireRegularFileForCleanup(file);
        Files.delete(file);
    }

    private static void requireRegularFileForCleanup(Path file) throws IOException {
        requireSafeAncestors(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("临时文件类型已改变，拒绝删除。");
    }

    private static long entryLimit(String name) {
        return switch (name) {
            case DATABASE_ENTRY -> MAX_DATABASE_BYTES;
            case SECRETS_ENTRY -> MAX_SECRETS_BYTES;
            case MANIFEST_ENTRY -> MAX_MANIFEST_BYTES;
            default -> 0;
        };
    }

    private static FileDigest writeEntry(ZipOutputStream archive, String name, InputStream input,
                                         long limit, BooleanSupplier cancelled) throws IOException {
        archive.putNextEntry(new ZipEntry(name));
        FileDigest digest = copy(input, archive, limit, cancelled);
        if (digest.bytes() < 1) throw new IOException("备份输入不能为空。");
        archive.closeEntry();
        return digest;
    }

    private static FileDigest copy(InputStream input, OutputStream output, long limit,
                                   BooleanSupplier cancelled) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[65536];
        long bytes = 0;
        try {
            int read;
            while ((read = input.read(buffer)) != -1) {
                checkCancelled(cancelled);
                if (bytes > limit - read) throw new IOException("备份条目或解密内容超过大小限制。");
                bytes += read;
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
            return new FileDigest(bytes, HexFormat.of().formatHex(digest.digest()));
        } finally { Arrays.fill(buffer, (byte) 0); }
    }

    private static byte[] readBounded(InputStream input, int limit, BooleanSupplier cancelled) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        copy(input, output, limit, cancelled);
        return output.toByteArray();
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) channel.write(buffer);
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (GeneralSecurityException unavailable) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    private record FileDigest(long bytes, String sha256) {
        boolean matches(FileDigest other) {
            return bytes == other.bytes && MessageDigest.isEqual(sha256.getBytes(StandardCharsets.US_ASCII),
                    other.sha256.getBytes(StandardCharsets.US_ASCII));
        }
    }

    private record Manifest(Instant createdAt, FileDigest database, FileDigest secrets) {
        private static final Set<String> KEYS = Set.of("manifest.version", "container.version", "database.format",
                "credential.scope", "created.at", "database.bytes", "database.sha256", "secrets.bytes", "secrets.sha256");

        byte[] encode() {
            return ("manifest.version=1\ncontainer.version=1\ndatabase.format=h2-aes\n"
                    + "credential.scope=windows-current-user\ncreated.at=" + createdAt + "\n"
                    + "database.bytes=" + database.bytes + "\ndatabase.sha256=" + database.sha256 + "\n"
                    + "secrets.bytes=" + secrets.bytes + "\nsecrets.sha256=" + secrets.sha256 + "\n").getBytes(StandardCharsets.UTF_8);
        }

        static Manifest decode(byte[] bytes) throws IOException {
            try {
                String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                Map<String, String> values = new HashMap<>();
                for (String line : text.split("\n")) {
                    int split = line.indexOf('=');
                    if (split <= 0 || !KEYS.contains(line.substring(0, split))
                            || values.putIfAbsent(line.substring(0, split), line.substring(split + 1)) != null) {
                        throw new IllegalArgumentException();
                    }
                }
                if (!values.keySet().equals(KEYS) || !"1".equals(values.get("manifest.version"))
                        || !"1".equals(values.get("container.version")) || !"h2-aes".equals(values.get("database.format"))
                        || !"windows-current-user".equals(values.get("credential.scope"))) throw new IllegalArgumentException();
                return new Manifest(Instant.parse(values.get("created.at")),
                        digest(values, "database", MAX_DATABASE_BYTES), digest(values, "secrets", MAX_SECRETS_BYTES));
            } catch (CharacterCodingException | RuntimeException invalid) {
                throw new IOException("备份版本清单无效或不兼容。");
            }
        }

        private static FileDigest digest(Map<String, String> values, String name, long limit) {
            long length = Long.parseLong(values.get(name + ".bytes"));
            String hash = values.get(name + ".sha256");
            if (length < 1 || length > limit || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
            return new FileDigest(length, hash);
        }
    }

    private static final class WindowsKeyProtector implements KeyProtector {
        @Override public byte[] protect(byte[] key) throws IOException {
            if (!Platform.isWindows()) throw new IOException("本地备份需要 Windows 用户加密服务。");
            byte[] entropy = machineEntropy();
            try { return Crypt32Util.cryptProtectData(key, entropy, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null, null); }
            catch (RuntimeException failure) { throw new IOException("Windows 无法保护备份密钥。"); }
            finally { Arrays.fill(entropy, (byte) 0); }
        }
        @Override public byte[] unprotect(byte[] wrapped) throws IOException {
            if (!Platform.isWindows()) throw new IOException("本地备份需要 Windows 用户加密服务。");
            byte[] entropy = machineEntropy();
            try { return Crypt32Util.cryptUnprotectData(wrapped, entropy, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null); }
            catch (RuntimeException failure) {
                throw new IOException("无法解密备份密钥，请在创建备份的同一台 Windows 电脑、同一用户下验证。");
            } finally { Arrays.fill(entropy, (byte) 0); }
        }

        private static byte[] machineEntropy() throws IOException {
            // Current-user DPAPI can roam in some domain setups. Additional local-machine entropy
            // deliberately prevents treating that behavior as supported cross-device recovery.
            byte[] identity = null;
            try {
                String guid = Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE,
                        "SOFTWARE\\Microsoft\\Cryptography", "MachineGuid");
                String canonical = UUID.fromString(guid).toString();
                identity = ("PsychCounselorBackup/v1/" + canonical).getBytes(StandardCharsets.UTF_8);
                return sha256().digest(identity);
            } catch (RuntimeException unavailable) {
                throw new IOException("无法读取本机的 Windows 加密标识，已取消备份操作。");
            } finally { if (identity != null) Arrays.fill(identity, (byte) 0); }
        }
    }

    /** Bounded authenticated records avoid SunJCE buffering an entire multi-GB GCM payload at decryption. */
    private static final class FramedOutput extends OutputStream {
        private final DataOutputStream output;
        private final byte[] key;
        private final byte[] noncePrefix = new byte[8];
        private final byte[] buffer = new byte[65536];
        private final byte[] header;
        private int used;
        private int counter;
        private long total;
        private boolean closed;

        private FramedOutput(OutputStream output, byte[] key, KeyProtector protector) throws IOException {
            this.output = new DataOutputStream(output);
            this.key = key.clone();
            new SecureRandom().nextBytes(noncePrefix);
            byte[] wrapped = null;
            try {
                wrapped = protector.protect(key);
                if (wrapped.length < 1 || wrapped.length > 65536) throw new IOException("备份密钥封装长度无效。");
                header = ByteBuffer.allocate(24 + wrapped.length).putInt(0x50534231).putInt(1)
                        .putInt(65536).put(noncePrefix).putInt(wrapped.length).put(wrapped).array();
                this.output.write(header);
            } catch (IOException | RuntimeException failure) {
                Arrays.fill(this.key, (byte) 0);
                throw failure;
            } finally { if (wrapped != null) Arrays.fill(wrapped, (byte) 0); }
        }

        @Override public void write(int value) throws IOException { write(new byte[]{(byte) value}, 0, 1); }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (closed) throw new IOException("备份加密流已关闭。");
            if (total > MAX_ZIP_BYTES - length) throw new IOException("备份 ZIP 超过大小限制。");
            total += length;
            while (length > 0) {
                int copied = Math.min(length, buffer.length - used);
                System.arraycopy(bytes, offset, buffer, used, copied);
                used += copied;
                offset += copied;
                length -= copied;
                if (used == buffer.length) writeFrame(used);
            }
        }

        private void writeFrame(int length) throws IOException {
            byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, key, header, noncePrefix, counter++, buffer, length);
            try {
                output.writeInt(length);
                output.write(encrypted);
                Arrays.fill(buffer, (byte) 0);
                used = 0;
            } finally { Arrays.fill(encrypted, (byte) 0); }
        }

        @Override public void flush() throws IOException { output.flush(); }
        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            try {
                if (used != 0) writeFrame(used);
                writeFrame(0); // Authenticated explicit final record: truncation and missing EOF are fatal.
                output.flush();
            } finally {
                Arrays.fill(key, (byte) 0);
                Arrays.fill(buffer, (byte) 0);
            }
            // Channel ownership stays with the caller so force(true) runs after ZIP and GCM finalization.
        }
    }

    private static final class FramedInput extends InputStream {
        private final DataInputStream input;
        private final byte[] header;
        private final byte[] noncePrefix;
        private byte[] key;
        private byte[] buffer = new byte[0];
        private int offset;
        private int counter;
        private long total;
        private boolean ended;
        private boolean closed;

        private FramedInput(InputStream input, KeyProtector protector) throws IOException {
            this.input = new DataInputStream(input);
            byte[] fixed = this.input.readNBytes(24);
            if (fixed.length != 24) throw new IOException("备份头部不完整。");
            ByteBuffer fields = ByteBuffer.wrap(fixed);
            if (fields.getInt() != 0x50534231 || fields.getInt() != 1 || fields.getInt() != 65536) {
                throw new IOException("备份格式或版本不支持。");
            }
            noncePrefix = new byte[8];
            fields.get(noncePrefix);
            int wrappedLength = fields.getInt();
            if (wrappedLength < 1 || wrappedLength > 65536) throw new IOException("备份密钥封装长度无效。");
            byte[] wrapped = this.input.readNBytes(wrappedLength);
            if (wrapped.length != wrappedLength) throw new IOException("备份头部不完整。");
            header = ByteBuffer.allocate(fixed.length + wrapped.length).put(fixed).put(wrapped).array();
            try {
                key = protector.unprotect(wrapped);
                if (key == null || key.length != 32) {
                    if (key != null) Arrays.fill(key, (byte) 0);
                    throw new IOException("备份密钥格式无效。");
                }
            } finally { Arrays.fill(wrapped, (byte) 0); }
        }

        @Override public int read() throws IOException {
            if (!ready()) return -1;
            return buffer[offset++] & 0xff;
        }

        @Override public int read(byte[] bytes, int start, int length) throws IOException {
            Objects.checkFromIndexSize(start, length, bytes.length);
            if (length == 0) return 0;
            if (!ready()) return -1;
            int copied = Math.min(length, buffer.length - offset);
            System.arraycopy(buffer, offset, bytes, start, copied);
            offset += copied;
            return copied;
        }

        private boolean ready() throws IOException {
            if (closed) throw new IOException("备份解密流已关闭。");
            if (offset < buffer.length) return true;
            if (ended) return false;
            Arrays.fill(buffer, (byte) 0);
            int length = input.readInt();
            if (length < 0 || length > 65536 || counter > MAX_ZIP_BYTES / 65536 + 2
                    || total > MAX_ZIP_BYTES - length) throw new IOException("备份加密分段超过限制。");
            byte[] encrypted = input.readNBytes(length + 16);
            if (encrypted.length != length + 16) throw new IOException("备份加密分段不完整。");
            try { buffer = crypt(Cipher.DECRYPT_MODE, key, header, noncePrefix, counter++, encrypted, encrypted.length); }
            finally { Arrays.fill(encrypted, (byte) 0); }
            offset = 0;
            total += length;
            if (length == 0) {
                ended = true;
                if (input.read() != -1) throw new IOException("备份结束标记后存在多余内容。");
                return false;
            }
            return true;
        }

        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            Arrays.fill(key, (byte) 0);
            Arrays.fill(buffer, (byte) 0);
            input.close();
        }
    }

    private static byte[] crypt(int mode, byte[] key, byte[] header, byte[] prefix,
                                int counter, byte[] bytes, int length) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128,
                    ByteBuffer.allocate(12).put(prefix).putInt(counter).array()));
            cipher.updateAAD(header);
            int plainLength = mode == Cipher.ENCRYPT_MODE ? length : length - 16;
            cipher.updateAAD(ByteBuffer.allocate(8).putInt(counter).putInt(plainLength).array());
            return cipher.doFinal(bytes, 0, length);
        } catch (GeneralSecurityException failure) {
            throw new IOException("备份加密认证失败，文件可能已损坏、截断或被修改。");
        }
    }
}
