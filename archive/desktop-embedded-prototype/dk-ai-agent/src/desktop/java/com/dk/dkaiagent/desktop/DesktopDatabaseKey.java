package com.dk.dkaiagent.desktop;

import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.WinCrypt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;

/** Database file password, stored separately from the API key and protected by user-scoped DPAPI. */
final class DesktopDatabaseKey {
    private static final int PASSWORD_LENGTH = 64;
    private static final String HEX = "0123456789abcdef";

    private DesktopDatabaseKey() { }

    static void requireKeyForExistingDatabase(Path dataDirectory) throws DatabaseKeyException {
        Path database = dataDirectory.resolve("counseling.mv.db");
        Path key = keyFile();
        // notExists is deliberately used: an inaccessible/unknown path must never count as absent.
        if (!Files.notExists(database) && Files.notExists(key)) {
            throw new DatabaseKeyException("已发现咨询数据库，但缺少本机数据库加密密钥。请保留 counseling.mv.db，"
                    + "不要删除或覆盖；旧版未加密数据库需要先迁移，已加密数据库需要恢复原 Windows 用户的密钥文件。");
        }
    }

    static char[] readOrCreate(Path dataDirectory) throws DatabaseKeyException {
        requireKeyForExistingDatabase(dataDirectory);
        Path file = keyFile();
        byte[] plain = null;
        byte[] encrypted = null;
        byte[] random = null;
        Path temporary = null;
        try {
            if (Files.exists(file)) {
                if (!Files.isRegularFile(file) || Files.size(file) == 0 || Files.size(file) > 65536) {
                    throw new IOException("Invalid database key file");
                }
                encrypted = Files.readAllBytes(file);
                // LOCAL_MACHINE is absent: a different Windows user cannot decrypt this blob.
                plain = Crypt32Util.cryptUnprotectData(encrypted, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN);
            } else {
                if (!Files.notExists(file) || !Files.notExists(dataDirectory.resolve("counseling.mv.db"))) {
                    throw new IOException("Cannot safely create a database key");
                }
                random = new byte[32];
                new SecureRandom().nextBytes(random);
                plain = new byte[PASSWORD_LENGTH];
                for (int i = 0; i < random.length; i++) {
                    int value = random[i] & 0xff;
                    plain[2 * i] = (byte) HEX.charAt(value >>> 4);
                    plain[2 * i + 1] = (byte) HEX.charAt(value & 0xf);
                }
                encrypted = Crypt32Util.cryptProtectData(plain, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN);
                Files.createDirectories(file.getParent());
                temporary = Files.createTempFile(file.getParent(), "database-key-", ".tmp");
                Files.write(temporary, encrypted);
                // Never replace a concurrently created key. Only encrypted bytes reach either file.
                Files.move(temporary, file);
            }
            if (plain.length != PASSWORD_LENGTH) throw new IOException("Invalid database key");
            char[] password = new char[PASSWORD_LENGTH];
            for (int i = 0; i < plain.length; i++) {
                char value = (char) plain[i];
                if (HEX.indexOf(value) < 0) {
                    Arrays.fill(password, '\0');
                    throw new IOException("Invalid database key");
                }
                password[i] = value;
            }
            return password;
        } catch (IOException | RuntimeException failure) {
            // A corrupt, moved or inaccessible key must not trigger generation of a replacement.
            throw new DatabaseKeyException("无法读取或保存 Windows 用户保护的数据库密钥。请保留数据库和 database-key.dpapi；"
                    + "检查数据目录权限，并使用创建该数据库的 Windows 账号。旧版数据库需要先迁移。");
        } finally {
            if (plain != null) Arrays.fill(plain, (byte) 0);
            if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
            if (random != null) Arrays.fill(random, (byte) 0);
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    private static Path keyFile() throws DatabaseKeyException {
        String local = System.getenv("LOCALAPPDATA");
        if (local == null || local.isBlank()) {
            throw new DatabaseKeyException("无法定位 LOCALAPPDATA，不能安全保存数据库加密密钥。请使用正常登录的 Windows 用户启动程序。");
        }
        Path directory = Path.of(local);
        if (!directory.isAbsolute()) throw new DatabaseKeyException("LOCALAPPDATA 必须是绝对路径。");
        return directory.resolve("PsychCounselor").resolve("database-key.dpapi");
    }

    static final class DatabaseKeyException extends IOException {
        DatabaseKeyException(String message) { super(message); }
    }
}
