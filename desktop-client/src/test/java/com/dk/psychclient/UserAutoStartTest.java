package com.dk.psychclient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class UserAutoStartTest {
    @TempDir Path directory;

    @Test void startupCommandQuotesTheExecutableAndStartsInBackground() throws IOException {
        MemoryRegistry registry = new MemoryRegistry();
        UserAutoStart autoStart = new UserAutoStart(registry);
        Path executable = directory.resolve("中文 安装目录/PsychCounselorServer.exe");
        assertFalse(autoStart.enabledFor(executable));
        autoStart.enable(executable);
        assertEquals('"' + executable.toString() + "\" --background", registry.value);
        assertTrue(autoStart.enabledFor(executable));
        assertFalse(autoStart.enabledFor(directory.resolve("other/PsychCounselorServer.exe")));
        autoStart.disable();
        assertTrue(autoStart.registeredCommand().isEmpty());
        autoStart.disable();
        assertTrue(autoStart.registeredCommand().isEmpty());
    }

    @Test void invalidCommandsCannotReplaceAWorkingRegistryValue() throws IOException {
        MemoryRegistry registry = new MemoryRegistry();
        UserAutoStart autoStart = new UserAutoStart(registry);
        Path installed = directory.resolve("PsychCounselorServer.exe");
        autoStart.enable(installed);
        String current = registry.value;
        assertThrows(IllegalArgumentException.class, () -> autoStart.enable(Path.of("PsychCounselorServer.exe")));
        assertThrows(IllegalArgumentException.class, () -> autoStart.enable(directory.resolve("start.cmd")));
        assertEquals(current, registry.value);
    }

    @Test void registryFailureIsReportedAndDoesNotPretendTheSettingWasSaved() {
        UserAutoStart autoStart = new UserAutoStart(new UserAutoStart.Registry() {
            @Override public Optional<String> read() throws IOException { throw new IOException("Denied"); }
            @Override public void write(String command) throws IOException { throw new IOException("Denied"); }
            @Override public void delete() throws IOException { throw new IOException("Denied"); }
        });
        assertThrows(IOException.class, autoStart::registeredCommand);
        assertThrows(IOException.class, () -> autoStart.enable(directory.resolve("PsychCounselorServer.exe")));
        assertThrows(IOException.class, autoStart::disable);
    }

    @Test void explicitOptOutSurvivesAMissingRegistryEntryAndCanBeReenabled() throws IOException {
        assertFalse(AutoStartPreference.isDisabled(directory));
        AutoStartPreference.setDisabled(directory, true);
        assertTrue(AutoStartPreference.isDisabled(directory));
        AutoStartPreference.setDisabled(directory, true);
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
        AutoStartPreference.setDisabled(directory, false);
        assertFalse(AutoStartPreference.isDisabled(directory));
    }

    private static final class MemoryRegistry implements UserAutoStart.Registry {
        String value;
        @Override public Optional<String> read() { return Optional.ofNullable(value); }
        @Override public void write(String command) { value = command; }
        @Override public void delete() { value = null; }
    }
}
