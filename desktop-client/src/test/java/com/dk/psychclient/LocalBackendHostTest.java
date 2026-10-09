package com.dk.psychclient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class LocalBackendHostTest {
    @TempDir Path directory;

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void prepareProtectsFreshStorageAndRetainsExclusiveLockUntilClose() throws Exception {
        LocalDataBackup.secureOwnedDirectory(directory);
        Path application = directory.resolve("PsychCounselor");
        Path data = application.resolve("data");
        Path lockPath = application.resolve("desktop.lock");

        try (LocalBackendHost host = LocalBackendHost.prepare(data)) {
            assertTrue(host.firstRun());
            assertFalse(host.hasApiKey());
            assertFalse(host.adminPasswordForDisplay().isBlank());
            LocalDataBackup.requirePrivatePath(application, true, true);
            LocalDataBackup.requirePrivatePath(data, true, true);
            LocalDataBackup.requirePrivatePath(application.resolve("secrets.dpapi"), false, true);
            LocalDataBackup.requirePrivatePath(lockPath, false, true);
            try (FileChannel competing = FileChannel.open(lockPath, StandardOpenOption.WRITE)) {
                assertThrows(OverlappingFileLockException.class, competing::tryLock);
            }
        }

        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            assertNotNull(lock);
            assertTrue(lock.isValid());
        }
    }

    @Test void workerStopFollowsSpringAndFailureRetainsLockAndBlocksApiKeyUpdates() throws Exception {
        Path application = Files.createDirectories(directory.resolve("PsychCounselor"));
        Path data = Files.createDirectories(application.resolve("data"));
        Path app = Files.createDirectories(directory.resolve("app"));
        Path jar = Files.createFile(app.resolve("psych-counselor-server.jar"));
        Files.createFile(Files.createDirectories(app.resolve("worker")).resolve("PsychCounselorWorker.exe"));
        List<String> order = new ArrayList<>();
        WorkerSidecarTest.FakeProcess child = new WorkerSidecarTest.FakeProcess(42);
        child.ignoreDestroy = true;
        child.ignoreForce = true;
        WorkerSidecarTest.FakeTransport transport = new WorkerSidecarTest.FakeTransport(42);
        transport.onShutdown = () -> order.add("worker");
        WorkerSidecar worker = new WorkerSidecar(jar, builder -> child, transport, Duration.ofSeconds(1), Duration.ZERO);
        assertTrue(worker.start(new char[0], () -> false));
        FileChannel channel = FileChannel.open(application.resolve("desktop.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = channel.tryLock();
        DpapiSecretStore.Material secrets = new DpapiSecretStore.Material(new char[64], new char[]{'k'}, new char[0]);
        AtomicBoolean springFail = new AtomicBoolean(true);
        LocalBackendHost host = new LocalBackendHost(data, new DpapiSecretStore(data), secrets, channel, lock,
                new Object(), (context, loader) -> {
                    order.add("spring");
                    if (springFail.get()) throw new IOException("Spring close failed");
                }, worker);
        assertThrows(IOException.class, host::stopBackendKeepingOwnership);
        assertEquals(List.of("spring"), order);
        assertTrue(child.isAlive());
        springFail.set(false);
        assertThrows(IOException.class, host::close);
        assertEquals(List.of("spring", "spring", "worker"), order);
        assertTrue(lock.isValid());
        assertEquals('k', secrets.apiKey[0]);
        assertThrows(IOException.class, () -> host.updateApiKey("new-key".toCharArray()));
        assertThrows(IOException.class, () -> host.start(() -> false));
        child.ignoreForce = false;
        host.close();
        assertFalse(lock.isValid());
        assertFalse(child.isAlive());
        assertEquals('\0', secrets.apiKey[0]);
    }

    @Test void closeFailureRetainsHostCredentialsAndExclusiveDataLockUntilRetry() throws Exception {
        Path application = Files.createDirectories(directory.resolve("PsychCounselor"));
        Path data = Files.createDirectories(application.resolve("data"));
        Path lockPath = application.resolve("desktop.lock");
        FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = channel.tryLock();
        assertNotNull(lock);

        AtomicBoolean failClose = new AtomicBoolean(true);
        DpapiSecretStore.Material secrets = new DpapiSecretStore.Material(
                new char[64], new char[]{'k'}, new char[0]);
        LocalBackendHost host = new LocalBackendHost(data, new DpapiSecretStore(data), secrets,
                channel, lock, new Object(), (context, loader) -> {
                    if (failClose.get()) throw new IOException("simulated context close failure");
                });

        assertSame(host, LocalServerManager.closeOwnedHost(host));
        assertTrue(lock.isValid());
        assertEquals('k', secrets.apiKey[0]);
        try (FileChannel competing = FileChannel.open(lockPath, StandardOpenOption.WRITE)) {
            assertThrows(OverlappingFileLockException.class, competing::tryLock);
        }

        failClose.set(false);
        assertNull(LocalServerManager.closeOwnedHost(host));
        assertFalse(lock.isValid());
        assertEquals('\0', secrets.apiKey[0]);
    }
}
