package com.dk.psychclient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

/** A disabled marker distinguishes an explicit opt-out from a first installation's absent Run entry. */
final class AutoStartPreference {
    private static final String FILE_NAME = "server-autostart.disabled";
    private AutoStartPreference() { }

    static boolean isDisabled(Path userDirectory) throws IOException {
        try {
            if (!Files.readAttributes(userDirectory.resolve(FILE_NAME), BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).isRegularFile()) {
                throw new IOException("登录自启动偏好格式无效。");
            }
            return true;
        } catch (NoSuchFileException absent) {
            return false;
        }
    }

    static void setDisabled(Path userDirectory, boolean disabled) throws IOException {
        Path file = userDirectory.resolve(FILE_NAME);
        if (!disabled) {
            Files.deleteIfExists(file);
            return;
        }
        Files.createDirectories(userDirectory);
        Path temporary = Files.createTempFile(userDirectory, "server-autostart-", ".tmp");
        try {
            Files.writeString(temporary, "登录自启动已由用户关闭。\n", StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
