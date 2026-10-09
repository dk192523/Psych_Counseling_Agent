package com.dk.psychclient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class WorkerSidecarTest {
    @TempDir Path directory;

    private Path installedJar() throws IOException {
        Path app = Files.createDirectories(directory.resolve("app"));
        Path jar = app.resolve("psych-counselor-server.jar");
        if (Files.notExists(jar)) Files.createFile(jar);
        Path worker = Files.createDirectories(app.resolve("worker")).resolve("PsychCounselorWorker.exe");
        if (Files.notExists(worker)) Files.createFile(worker);
        return jar;
    }

    @Test void resolvesOnlyTheServerJarSiblingWorkerAndMissingWorkerDoesNotLaunch() throws Exception {
        Path jar = installedJar();
        assertEquals(jar.getParent().resolve("worker/PsychCounselorWorker.exe"), WorkerSidecar.installedExecutable(jar));
        assertThrows(IOException.class, () -> WorkerSidecar.installedExecutable(jar.getParent()));
        Path other = Files.createFile(jar.getParent().resolve("custom.jar"));
        assertThrows(IOException.class, () -> WorkerSidecar.installedExecutable(other));
        Files.delete(jar.getParent().resolve("worker/PsychCounselorWorker.exe"));
        AtomicBoolean launched = new AtomicBoolean();
        WorkerSidecar worker = new WorkerSidecar(jar, builder -> {
            launched.set(true);
            throw new AssertionError("Missing Worker must use Java fallback");
        }, new FakeTransport(42), Duration.ZERO, Duration.ZERO);
        assertFalse(worker.start(new char[0], () -> false));
        assertFalse(launched.get());
        assertFalse(worker.hasProcess());
    }

    @Test void environmentDropsInheritedAuthModelTranscriptAndPythonInjection() {
        Map<String, String> environment = new HashMap<>(Map.of(
                "AI_WORKER_ALLOW_UNAUTHENTICATED", "true", "ai_worker_shared_secret", "inherited",
                "DEEPSEEK_API_KEY", "old", "DEEPSEEK_BASE_URL", "https://untrusted.invalid",
                "COUNSELING_TRANSCRIPT_DIRECTORY", "private", "PYTHONPATH", "inject",
                "_PYI_APPLICATION_HOME_DIR", "inject", "VIRTUAL_ENV", "inject",
                "SystemRoot", "C:\\Windows"));
        WorkerSidecar.prepareEnvironment(environment, "fresh-token", "new-key".toCharArray(), 43210);
        assertEquals(Map.of("SystemRoot", "C:\\Windows", "AI_WORKER_SHARED_SECRET", "fresh-token",
                "DEEPSEEK_API_KEY", "new-key", "AI_WORKER_PORT", "43210"), environment);
    }

    @Test void readinessRequiresSuccessfulStatusUniqueIntegerPidAndExactOwnedProcess() {
        assertTrue(WorkerSidecar.validReadiness(new WorkerSidecar.Reply(200,
                "{\"status\":\"ok\",\"pid\":42,\"heuristicPlanner\":true,\"llmConfigured\":false}"), 42));
        for (String invalid : List.of(
                "{\"status\":\"ok\",\"pid\":43}", "{\"status\":\"ok\",\"pid\":\"42\"}",
                "{\"status\":\"ok\",\"pid\":42.0}", "{\"status\":\"ok\",\"pid\":42,\"pid\":43}",
                "{\"status\":\"ok\",\"pid\":42,}", "{\"status\":\"down\",\"pid\":42}",
                "{\"status\":\"ok\",\"other\":{\"pid\":42}}", "prefix {\"status\":\"ok\",\"pid\":42}")) {
            assertFalse(WorkerSidecar.validReadiness(new WorkerSidecar.Reply(200, invalid), 42), invalid);
        }
        assertFalse(WorkerSidecar.validReadiness(new WorkerSidecar.Reply(302, "{\"status\":\"ok\",\"pid\":42}"), 42));
    }

    @Test void verifiedStartupUsesOnlyExecutableArgumentFreshEnvTokenAndLoopback() throws Exception {
        Path jar = installedJar();
        List<String> tokens = new ArrayList<>();
        FakeTransport transport = new FakeTransport(42);
        WorkerSidecar worker = new WorkerSidecar(jar, builder -> {
            assertEquals(List.of(jar.getParent().resolve("worker/PsychCounselorWorker.exe").toString()), builder.command());
            String token = builder.environment().get("AI_WORKER_SHARED_SECRET");
            assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
            tokens.add(token);
            assertEquals("key", builder.environment().get("DEEPSEEK_API_KEY"));
            assertEquals(ProcessBuilder.Redirect.DISCARD, builder.redirectOutput());
            return new FakeProcess(42);
        }, transport, Duration.ofSeconds(1), Duration.ZERO);
        for (int launch = 0; launch < 2; launch++) {
            assertTrue(worker.start("key".toCharArray(), () -> false));
            assertEquals("127.0.0.1", worker.baseUri().getHost());
            assertTrue(worker.processAlive().getAsBoolean());
            worker.stopAndConfirm();
            assertEquals(tokens.get(launch), transport.shutdownToken);
            assertFalse(worker.hasProcess());
        }
        assertNotEquals(tokens.get(0), tokens.get(1));
    }

    @Test void wrongPidAndExitedChildDisableWorkerAfterCleanupWithoutSendingShutdownToOtherProcess() throws Exception {
        for (boolean alreadyExited : List.of(false, true)) {
            FakeProcess child = new FakeProcess(42);
            if (alreadyExited) child.exit();
            FakeTransport transport = new FakeTransport(999);
            WorkerSidecar worker = new WorkerSidecar(installedJar(), builder -> child, transport,
                    Duration.ofMillis(5), Duration.ZERO);
            assertFalse(worker.start(new char[0], () -> false));
            assertFalse(child.isAlive());
            assertFalse(worker.hasProcess());
            assertNull(transport.shutdownToken);
        }
    }

    @Test void launchFailureAllowsFallbackAndRuntimeExitClosesPreviouslySharedGate() throws Exception {
        Path jar = installedJar();
        WorkerSidecar failed = new WorkerSidecar(jar, builder -> { throw new IOException("start failed"); },
                new FakeTransport(42), Duration.ZERO, Duration.ZERO);
        assertFalse(failed.start(new char[0], () -> false));
        assertFalse(failed.hasProcess());
        FakeProcess child = new FakeProcess(42);
        WorkerSidecar worker = new WorkerSidecar(jar, builder -> child, new FakeTransport(42),
                Duration.ofSeconds(1), Duration.ZERO);
        assertTrue(worker.start(new char[0], () -> false));
        BooleanSupplier gate = worker.processAlive();
        child.exit();
        assertFalse(gate.getAsBoolean());
        assertFalse(worker.isReady());
        worker.stopAndConfirm();
    }

    @Test void forceTerminationAndUnconfirmedDeathRetainReferenceAndPreventRestart() throws Exception {
        FakeProcess child = new FakeProcess(42);
        child.ignoreDestroy = true;
        child.ignoreForce = true;
        FakeTransport transport = new FakeTransport(42);
        WorkerSidecar worker = new WorkerSidecar(installedJar(), builder -> child, transport,
                Duration.ofSeconds(1), Duration.ZERO);
        assertTrue(worker.start(new char[0], () -> false));
        BooleanSupplier gate = worker.processAlive();
        assertThrows(IOException.class, worker::stopAndConfirm);
        assertTrue(child.forced);
        assertTrue(worker.hasProcess());
        assertFalse(gate.getAsBoolean());
        assertThrows(IOException.class, () -> worker.start(new char[0], () -> false));
        child.ignoreForce = false;
        worker.stopAndConfirm();
        assertFalse(child.isAlive());
        assertFalse(worker.hasProcess());
    }

    static final class FakeTransport implements WorkerSidecar.Transport {
        private final long responsePid;
        String shutdownToken;
        Runnable onShutdown = () -> {};
        FakeTransport(long responsePid) { this.responsePid = responsePid; }
        @Override public WorkerSidecar.Reply ready(URI uri) {
            assertEquals("127.0.0.1", uri.getHost());
            return new WorkerSidecar.Reply(200, "{\"status\":\"ok\",\"pid\":" + responsePid + "}");
        }
        @Override public void shutdown(URI uri, String token) {
            assertEquals("/internal/v1/admin/shutdown", uri.getPath());
            shutdownToken = token;
            onShutdown.run();
        }
    }

    static final class FakeProcess extends Process {
        private final long pid;
        private final CompletableFuture<Process> exit = new CompletableFuture<>();
        boolean alive = true;
        boolean ignoreDestroy;
        boolean ignoreForce;
        boolean forced;
        FakeProcess(long pid) { this.pid = pid; }
        void exit() { alive = false; exit.complete(this); }
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { return exitValue(); }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return !alive; }
        @Override public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
        @Override public void destroy() { if (!ignoreDestroy) exit(); }
        @Override public Process destroyForcibly() { forced = true; if (!ignoreForce) exit(); return this; }
        @Override public boolean isAlive() { return alive; }
        @Override public long pid() { return pid; }
        @Override public CompletableFuture<Process> onExit() { return exit; }
    }
}
