package com.dk.psychclient;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** HKCU Run affects only the current Windows account and never needs an administrator. */
final class UserAutoStart {
    static final String RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    static final String VALUE_NAME = "PsychCounselorServer";
    private final Registry registry;

    interface Registry {
        Optional<String> read() throws IOException;
        void write(String command) throws IOException;
        void delete() throws IOException;
    }

    UserAutoStart(Registry registry) { this.registry = Objects.requireNonNull(registry); }

    static UserAutoStart forCurrentUser() {
        return new UserAutoStart(new Registry() {
            @Override public Optional<String> read() throws IOException {
                return nativeCall(() -> {
                    if (!Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)) {
                        return Optional.empty();
                    }
                    return Optional.of(Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER,
                            RUN_KEY, VALUE_NAME));
                });
            }

            @Override public void write(String command) throws IOException {
                nativeCall(() -> {
                    Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, RUN_KEY);
                    Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME, command);
                    return null;
                });
            }

            @Override public void delete() throws IOException {
                nativeCall(() -> {
                    if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)) {
                        Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME);
                    }
                    return null;
                });
            }
        });
    }

    Optional<String> registeredCommand() throws IOException { return registry.read(); }

    boolean enabledFor(Path executable) throws IOException {
        return registry.read().filter(command(executable)::equals).isPresent();
    }

    void enable(Path executable) throws IOException { registry.write(command(executable)); }

    void disable() throws IOException { registry.delete(); }

    static String command(Path executable) {
        Objects.requireNonNull(executable, "executable");
        if (!executable.isAbsolute()) throw new IllegalArgumentException("自启动程序必须使用绝对路径。");
        String value = executable.normalize().toString();
        if (!value.toLowerCase(Locale.ROOT).endsWith(".exe")
                || value.indexOf('"') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("自启动程序路径无效。");
        }
        // Quoting the executable prevents paths containing spaces from changing the selected program.
        return '"' + value + "\" --background";
    }

    static Optional<Path> currentLauncher() {
        return ProcessHandle.current().info().command().map(Path::of)
                .filter(path -> path.getFileName().toString().equalsIgnoreCase("PsychCounselorServer.exe"))
                .filter(Files::isRegularFile);
    }

    private interface NativeOperation<T> { T run(); }

    private static <T> T nativeCall(NativeOperation<T> operation) throws IOException {
        if (!Platform.isWindows()) throw new IOException("登录自启动仅支持 Windows。");
        try { return operation.run(); }
        catch (RuntimeException failure) {
            throw new IOException("无法更新当前 Windows 用户的登录自启动设置。");
        }
    }
}
