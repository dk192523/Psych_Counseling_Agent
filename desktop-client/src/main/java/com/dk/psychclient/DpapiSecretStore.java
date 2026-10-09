package com.dk.psychclient;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.WinCrypt;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Arrays;

/** Windows user-scoped DPAPI; plaintext never goes to a property file or command line. */
final class DpapiSecretStore {
    private static final int MAGIC = 0x50534431;
    private static final int MAX_KEY_CHARS = 8192;
    private final Path file;
    private final Path dataDirectory;

    DpapiSecretStore(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
        this.file = dataDirectory.getParent().resolve("secrets.dpapi");
    }

    Material loadOrCreate() throws IOException {
        if (!Platform.isWindows()) throw new IOException("本地模式需要 Windows 用户加密服务。");
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return loadExisting();
        // Missing credentials must never silently replace the key for an existing encrypted DB.
        if (Files.isDirectory(dataDirectory)) {
            try (var files = Files.walk(dataDirectory)) {
                if (files.anyMatch(Files::isRegularFile)) {
                    throw new IOException("本地数据已存在但凭据文件缺失，请恢复原 secrets.dpapi 后再启动。");
                }
            }
        }
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        char[] database = new char[64];
        char[] hex = "0123456789abcdef".toCharArray();
        for (int i = 0; i < random.length; i++) {
            database[2 * i] = hex[(random[i] & 0xff) >>> 4];
            database[2 * i + 1] = hex[random[i] & 0x0f];
        }
        Arrays.fill(random, (byte) 0);
        char[] alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#".toCharArray();
        char[] admin = new char[24];
        SecureRandom rng = new SecureRandom();
        for (int i = 0; i < admin.length; i++) admin[i] = alphabet[rng.nextInt(alphabet.length)];
        Material material = new Material(database, new char[0], admin);
        try {
            save(material);
            return material;
        } catch (IOException | RuntimeException failure) {
            material.close();
            throw failure;
        }
    }

    /** Existing data must never take the key-generation path, including an isolated restore drill. */
    Material loadExisting() throws IOException {
        return loadExistingFile(file);
    }

    /** Reads the explicitly named copy; does not create directories, credentials, or database files. */
    static Material loadExistingFile(Path existingFile) throws IOException {
        if (!Platform.isWindows()) throw new IOException("本地模式需要 Windows 用户加密服务。");
        if (!Files.isRegularFile(existingFile, LinkOption.NOFOLLOW_LINKS)
                || Files.size(existingFile) < 1 || Files.size(existingFile) > 128 * 1024) {
            throw new IOException("本地凭据文件缺失或格式无效，不会生成替代密钥。");
        }
        byte[] encrypted;
        try (var input = Files.newInputStream(existingFile, LinkOption.NOFOLLOW_LINKS)) {
            encrypted = input.readNBytes(128 * 1024 + 1);
        }
        byte[] plain = null;
        try {
            if (encrypted.length > 128 * 1024) throw new IOException("本地凭据文件格式无效。");
            plain = Crypt32Util.cryptUnprotectData(encrypted, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN);
            ByteBuffer buffer = ByteBuffer.wrap(plain);
            if (buffer.getInt() != MAGIC) throw new IOException("本地凭据文件版本无效。");
            char[] database = readChars(buffer, 64);
            char[] api = null;
            char[] admin = null;
            try {
                api = readChars(buffer, MAX_KEY_CHARS);
                admin = readChars(buffer, 72);
                if (buffer.hasRemaining() || database.length != 64) throw new IOException("本地凭据格式无效。");
                for (char c : database) {
                    if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                        throw new IOException("本地数据库密钥格式无效。");
                    }
                }
                return new Material(database, api, admin);
            } catch (IOException | RuntimeException failure) {
                Arrays.fill(database, '\0');
                if (api != null) Arrays.fill(api, '\0');
                if (admin != null) Arrays.fill(admin, '\0');
                throw failure;
            }
        } catch (RuntimeException failure) {
            // Never surface native exception details, ciphertext or decrypted values to the UI.
            throw new IOException("无法解密本地凭据。请使用创建数据的 Windows 账号，并保留原凭据文件。");
        } finally {
            Arrays.fill(encrypted, (byte) 0);
            if (plain != null) Arrays.fill(plain, (byte) 0);
        }
    }

    void save(Material material) throws IOException {
        if (material.apiKey.length > MAX_KEY_CHARS) throw new IOException("API Key 长度超过限制。");
        byte[] plain = new byte[16 + 2 * (material.databasePassword.length
                + material.apiKey.length + material.pendingAdminPassword.length)];
        byte[] encrypted = null;
        Path temporary = null;
        try {
            ByteBuffer buffer = ByteBuffer.wrap(plain);
            buffer.putInt(MAGIC);
            writeChars(buffer, material.databasePassword);
            writeChars(buffer, material.apiKey);
            writeChars(buffer, material.pendingAdminPassword);
            // No CRYPTPROTECT_LOCAL_MACHINE flag: only this Windows user can decrypt the bundle.
            encrypted = Crypt32Util.cryptProtectData(plain, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN);
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), "secrets-", ".tmp");
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer ciphertext = ByteBuffer.wrap(encrypted);
                while (ciphertext.hasRemaining()) output.write(ciphertext);
                output.force(true);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("凭据目录不支持原子保存，已保留原凭据，请使用本地磁盘。", unsupported);
            }
        } catch (RuntimeException failure) {
            throw new IOException("无法通过 Windows 用户加密服务保存本地凭据。");
        } finally {
            Arrays.fill(plain, (byte) 0);
            if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
            if (temporary != null) Files.deleteIfExists(temporary);
        }
    }

    private static char[] readChars(ByteBuffer buffer, int limit) throws IOException {
        int length = buffer.getInt();
        if (length < 0 || length > limit || length > buffer.remaining() / 2) {
            throw new IOException("本地凭据格式无效。");
        }
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) chars[i] = buffer.getChar();
        return chars;
    }

    private static void writeChars(ByteBuffer buffer, char[] chars) {
        buffer.putInt(chars.length);
        for (char c : chars) buffer.putChar(c);
    }

    static final class Material implements AutoCloseable {
        final char[] databasePassword;
        char[] apiKey;
        char[] pendingAdminPassword;

        Material(char[] databasePassword, char[] apiKey, char[] pendingAdminPassword) {
            this.databasePassword = databasePassword;
            this.apiKey = apiKey;
            this.pendingAdminPassword = pendingAdminPassword;
        }

        @Override public void close() {
            Arrays.fill(databasePassword, '\0');
            Arrays.fill(apiKey, '\0');
            Arrays.fill(pendingAdminPassword, '\0');
        }
    }
}
