package com.dk.dkaiagent.desktop;

import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.WinCrypt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/** Small user-scoped DPAPI blob. Never persists a plaintext key or a database password. */
final class DesktopSecrets {
    private final Path file;

    DesktopSecrets(Path dataDirectory) { file = dataDirectory.resolve("secrets.dat"); }

    String readApiKey() throws IOException {
        if (!Files.exists(file)) return "";
        if (Files.size(file) > 65536) throw new IOException("Invalid desktop secrets size");
        byte[] encrypted = Files.readAllBytes(file);
        byte[] plain = null;
        try {
            plain = Crypt32Util.cryptUnprotectData(encrypted, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN);
            return new String(plain, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(encrypted, (byte) 0);
            if (plain != null) Arrays.fill(plain, (byte) 0);
        }
    }

    void saveApiKey(String key) throws IOException {
        Files.createDirectories(file.getParent());
        byte[] plain = key.getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = null;
        Path temporary = null;
        try {
            // LOCAL_MACHINE is deliberately absent: only the current Windows user can decrypt it.
            encrypted = Crypt32Util.cryptProtectData(plain, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN);
            temporary = Files.createTempFile(file.getParent(), "secrets-", ".tmp");
            Files.write(temporary, encrypted);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Arrays.fill(plain, (byte) 0);
            if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
            if (temporary != null) Files.deleteIfExists(temporary);
        }
    }
}
