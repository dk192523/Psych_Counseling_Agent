package com.dk.psychclient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Owns only the installed Server's fixed Worker executable; no user-supplied command or path. */
final class WorkerSidecar {
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);
    private static final Pattern JSON_MEMBER = Pattern.compile(
            "\\\"([^\\\"\\\\]*)\\\"\\s*:\\s*(\\\"(?:[^\\\"\\\\\\p{Cntrl}]|\\\\[\\\"\\\\/bfnrt]|\\\\u[0-9a-fA-F]{4})*\\\"|-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?|true|false|null)");
    private final Path serverJar;
    private final ProcessStarter starter;
    private final Transport transport;
    private final Duration readyTimeout;
    private final Duration stopTimeout;
    private Process process;
    private URI baseUri;
    private String token;
    private boolean ready;
    private AtomicBoolean requestGate = new AtomicBoolean();

    @FunctionalInterface interface ProcessStarter {
        Process start(ProcessBuilder builder) throws IOException;
    }
    interface Transport {
        Reply ready(URI uri) throws IOException, InterruptedException;
        void shutdown(URI uri, String token) throws IOException, InterruptedException;
    }
    record Reply(int status, String body) {}

    private WorkerSidecar(Path serverJar) {
        this(serverJar, ProcessBuilder::start, new HttpTransport(), READY_TIMEOUT, STOP_TIMEOUT);
    }

    // Package-private seams replace process/HTTP boundaries without booting Python in unit tests.
    WorkerSidecar(Path serverJar, ProcessStarter starter, Transport transport,
                  Duration readyTimeout, Duration stopTimeout) {
        this.serverJar = serverJar;
        this.starter = starter;
        this.transport = transport;
        this.readyTimeout = readyTimeout;
        this.stopTimeout = stopTimeout;
    }

    static WorkerSidecar installed() {
        Path jar = null;
        try {
            URI source = WorkerSidecar.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            if ("file".equalsIgnoreCase(source.getScheme())) jar = Path.of(source);
        } catch (Exception ignored) {
            // Development class directories and untrusted locations simply use the Java fallback.
        }
        return new WorkerSidecar(jar);
    }

    static Path installedExecutable(Path jar) throws IOException {
        if (jar == null) throw new IOException("未安装桌面 Worker。");
        Path normalized = jar.toAbsolutePath().normalize();
        if (!"psych-counselor-server.jar".equals(normalized.getFileName().toString())
                || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)
                || !normalized.equals(normalized.toRealPath())) {
            throw new IOException("桌面 Worker 安装位置无效。");
        }
        Path executable = normalized.getParent().resolve("worker/PsychCounselorWorker.exe");
        if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)
                || !executable.equals(executable.toRealPath())) {
            throw new IOException("未安装桌面 Worker。");
        }
        return executable;
    }

    /** Returns false after a safely cleaned startup failure. An unconfirmed live child is never discarded. */
    boolean start(char[] apiKey, BooleanSupplier cancelled) throws IOException {
        if (process != null) throw new IOException("Worker 尚未确认停止，不能重复启动。");
        try {
            checkCancelled(cancelled);
            Path executable = installedExecutable(serverJar);
            int port;
            try (ServerSocket reservation = new ServerSocket()) {
                reservation.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
                port = reservation.getLocalPort();
            }
            byte[] entropy = new byte[32];
            new SecureRandom().nextBytes(entropy);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
            java.util.Arrays.fill(entropy, (byte) 0);
            baseUri = URI.create("http://127.0.0.1:" + port);
            ProcessBuilder builder = new ProcessBuilder(executable.toString())
                    .directory(executable.getParent().toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            prepareEnvironment(builder.environment(), token, apiKey, port);
            try { process = starter.start(builder); }
            finally { removeInjectedEnvironment(builder.environment()); }
            process.getOutputStream().close();
            AtomicBoolean gate = new AtomicBoolean();
            requestGate = gate;
            process.onExit().thenRun(() -> gate.set(false));
            long deadline = System.nanoTime() + readyTimeout.toNanos();
            while (System.nanoTime() < deadline) {
                checkCancelled(cancelled);
                if (!process.isAlive()) throw new IOException("Worker 在就绪前退出。");
                try {
                    Reply response = transport.ready(baseUri.resolve("/internal/v1/health/ready"));
                    if (validReadiness(response, process.pid()) && process.isAlive()) {
                        ready = true;
                        gate.set(true);
                        if (process.isAlive() && gate.get()) return true;
                        throw new IOException("Worker 在就绪时退出。");
                    }
                } catch (IOException unavailable) {
                    // Binding/readiness can lag process launch. Do not log response bodies or credentials.
                }
                Thread.sleep(100);
            }
            throw new IOException("Worker 未能按时就绪。");
        } catch (Exception failure) {
            requestGate.set(false);
            ready = false;
            boolean interrupted = failure instanceof InterruptedException || Thread.interrupted();
            try {
                terminateAndConfirm();
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
            if (failure instanceof CancellationException cancelledFailure) throw cancelledFailure;
            if (interrupted) throw new CancellationException("Worker 启动已取消。");
            return false;
        }
    }

    static void prepareEnvironment(Map<String, String> environment, String token, char[] apiKey, int port) {
        removeInjectedEnvironment(environment);
        environment.put("AI_WORKER_SHARED_SECRET", token);
        environment.put("DEEPSEEK_API_KEY", new String(apiKey));
        environment.put("AI_WORKER_PORT", Integer.toString(port));
    }

    private static void removeInjectedEnvironment(Map<String, String> environment) {
        environment.keySet().removeIf(name -> {
            String upper = name.toUpperCase(Locale.ROOT);
            return upper.startsWith("AI_WORKER_") || upper.startsWith("DEEPSEEK_")
                    || upper.equals("COUNSELING_TRANSCRIPT_DIRECTORY") || upper.startsWith("PYTHON")
                    || upper.startsWith("PYI_") || upper.startsWith("_PYI_")
                    || upper.equals("__PYVENV_LAUNCHER__") || upper.startsWith("VIRTUAL_ENV");
        });
    }

    /** Strictly parse a small flat readiness object; substrings, duplicate keys and noninteger PIDs fail closed. */
    static boolean validReadiness(Reply response, long expectedPid) {
        if (response.status() != 200 || response.body() == null || response.body().length() > 4096) return false;
        String body = response.body().trim();
        if (!body.startsWith("{") || !body.endsWith("}")) return false;
        String fields = body.substring(1, body.length() - 1).trim();
        Map<String, String> values = new HashMap<>();
        int position = 0;
        while (position < fields.length()) {
            Matcher member = JSON_MEMBER.matcher(fields).region(position, fields.length());
            if (!member.lookingAt() || values.putIfAbsent(member.group(1), member.group(2)) != null) return false;
            position = member.end();
            while (position < fields.length() && Character.isWhitespace(fields.charAt(position))) position++;
            if (position == fields.length()) break;
            if (fields.charAt(position++) != ',') return false;
            while (position < fields.length() && Character.isWhitespace(fields.charAt(position))) position++;
            if (position == fields.length()) return false;
        }
        String pid = values.get("pid");
        if (!"\"ok\"".equals(values.get("status")) || pid == null || !pid.matches("[1-9][0-9]*")) return false;
        try { return Long.parseLong(pid) == expectedPid; }
        catch (NumberFormatException invalid) { return false; }
    }

    boolean hasProcess() { return process != null; }
    boolean isReady() { return ready && process != null && process.isAlive() && requestGate.get(); }
    URI baseUri() { return baseUri; }
    String sharedSecret() { return token; }
    BooleanSupplier processAlive() {
        Process owned = process;
        AtomicBoolean gate = requestGate;
        return () -> owned != null && gate.get() && owned.isAlive();
    }

    /** Call only after Spring closure is confirmed, so in-flight business calls drain first. */
    void stopAndConfirm() throws IOException {
        requestGate.set(false);
        boolean interrupted = Thread.interrupted();
        try {
            if (process != null && process.isAlive() && ready) {
                try {
                    transport.shutdown(baseUri.resolve("/internal/v1/admin/shutdown"), token);
                    process.waitFor(stopTimeout.toMillis(), TimeUnit.MILLISECONDS);
                } catch (IOException unavailable) {
                    // Exit can race the management request; the owned Process remains authoritative.
                } catch (InterruptedException cancelled) {
                    interrupted = true;
                }
            }
            terminateAndConfirm();
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void terminateAndConfirm() throws IOException {
        boolean interrupted = Thread.interrupted();
        try {
            if (process != null && process.isAlive()) {
                process.destroy();
                try { process.waitFor(stopTimeout.toMillis(), TimeUnit.MILLISECONDS); }
                catch (InterruptedException cancelled) { interrupted = true; }
                if (process.isAlive()) {
                    process.destroyForcibly();
                    try { process.waitFor(stopTimeout.toMillis(), TimeUnit.MILLISECONDS); }
                    catch (InterruptedException cancelled) { interrupted = true; }
                }
                if (process.isAlive()) throw new IOException("Worker 未确认关闭，已保留运行锁并取消备份及重复启动。");
            }
            process = null;
            ready = false;
            token = null;
            baseUri = null;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    private static final class HttpTransport implements Transport {
        private static HttpClient client() {
            return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
                    .proxy(ProxySelector.of(null)).followRedirects(HttpClient.Redirect.NEVER)
                    .version(HttpClient.Version.HTTP_1_1).build();
        }
        @Override public Reply ready(URI uri) throws IOException, InterruptedException {
            try (HttpClient client = client()) {
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
                return new Reply(response.statusCode(), response.body());
            }
        }
        @Override public void shutdown(URI uri, String token) throws IOException, InterruptedException {
            try (HttpClient client = client()) {
                client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2))
                        .header("X-AI-Worker-Token", token).POST(HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.discarding());
            }
        }
    }
}
