package com.dk.psychclient;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Owns one in-process backend. All lifecycle methods run on its owner's background executor. */
final class LocalBackendHost implements AutoCloseable {
    private static final String BOUNDARY_SOURCE = "desktopProcessBoundary";
    private final Path dataDirectory;
    private final DpapiSecretStore store;
    private final DpapiSecretStore.Material secrets;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final Map<String, Object> boundary = new HashMap<>();
    private Object context;
    private WorkerSidecar worker;
    private ClassLoader backendLoader;
    private final BackendCloser backendCloser;

    @FunctionalInterface interface BackendCloser {
        void closeAndConfirm(Object context, ClassLoader loader) throws Exception;
    }

    private LocalBackendHost(Path directory, DpapiSecretStore store,
                             DpapiSecretStore.Material secrets, FileChannel channel, FileLock lock) {
        this(directory, store, secrets, channel, lock, null, LocalBackendHost::closeSpringContext);
    }

    // Lifecycle failure tests do not need to boot Spring or decrypt production credentials.
    LocalBackendHost(Path directory, DpapiSecretStore store, DpapiSecretStore.Material secrets,
                     FileChannel channel, FileLock lock, Object context, BackendCloser backendCloser) {
        this(directory, store, secrets, channel, lock, context, backendCloser, null);
    }

    LocalBackendHost(Path directory, DpapiSecretStore store, DpapiSecretStore.Material secrets,
                     FileChannel channel, FileLock lock, Object context, BackendCloser backendCloser,
                     WorkerSidecar worker) {
        this.dataDirectory = directory;
        this.store = store;
        this.secrets = secrets;
        this.lockChannel = channel;
        this.lock = lock;
        this.context = context;
        this.worker = worker;
        this.backendCloser = backendCloser;
    }

    static LocalBackendHost prepare(Path directory) throws IOException {
        Path normalized = directory.toAbsolutePath().normalize();
        Path applicationDirectory = normalized.getParent();
        if (applicationDirectory == null) throw new IOException("本地数据目录无效。");
        Files.createDirectories(applicationDirectory);
        // The lock and all subsequent children must inherit the application-owned ACL.
        LocalDataBackup.secureOwnedDirectory(applicationDirectory);
        FileChannel channel = FileChannel.open(applicationDirectory.resolve("desktop.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        FileLock acquired = null;
        DpapiSecretStore.Material material = null;
        try {
            acquired = channel.tryLock();
            if (acquired == null) throw new IOException("本地咨询服务已在另一个窗口中运行，请先关闭它。");
            // Existing data is checked and protected only while this process owns desktop.lock.
            LocalDataBackup.secureApplicationStorage(applicationDirectory, normalized);
            if (Files.notExists(normalized, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(normalized);
            LocalDataBackup.secureOwnedDirectory(normalized);
            DpapiSecretStore store = new DpapiSecretStore(normalized);
            material = store.loadOrCreate();
            // Also protect a freshly written or atomically replaced secrets.dpapi.
            LocalDataBackup.secureApplicationStorage(applicationDirectory, normalized);
            return new LocalBackendHost(normalized, store, material, channel, acquired);
        } catch (IOException | RuntimeException | Error failure) {
            if (material != null) material.close();
            if (acquired != null) {
                try { acquired.release(); }
                catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            }
            try { channel.close(); }
            catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            if (failure instanceof OverlappingFileLockException) {
                throw new IOException("本地咨询服务已在另一个窗口中运行，请先关闭它。", failure);
            }
            throw failure;
        }
    }

    boolean firstRun() { return secrets.pendingAdminPassword.length != 0; }
    boolean hasApiKey() { return secrets.apiKey.length != 0; }
    String adminPasswordForDisplay() { return new String(secrets.pendingAdminPassword); }

    void updateApiKey(char[] key) throws IOException {
        if (context != null || worker != null) throw new IOException("请先确认本地服务及 Worker 已停止，再更新 API Key。");
        char[] previous = secrets.apiKey;
        secrets.apiKey = key.clone();
        try {
            store.save(secrets);
            Arrays.fill(previous, '\0');
        } catch (IOException | RuntimeException failure) {
            Arrays.fill(secrets.apiKey, '\0');
            secrets.apiKey = previous;
            throw failure;
        }
    }

    ServerAddress start(BooleanSupplier cancelled) throws Exception {
        return start(cancelled, true);
    }

    ServerAddress start(BooleanSupplier cancelled, boolean serveUi) throws Exception {
        checkCancelled(cancelled);
        if (context != null || worker != null) throw new IOException("本地服务或 Worker 尚未确认停止，不能重复启动。");
        if (!lock.isValid()) throw new IOException("本地服务已失去数据目录的运行锁。");
        backendLoader = Thread.currentThread().getContextClassLoader();
        Class<?> applicationClass = Class.forName("com.dk.dkaiagent.DkAiAgentApplication", true, backendLoader);
        Class<?> springClass = Class.forName("org.springframework.boot.SpringApplication", true, backendLoader);
        Class<?> environmentClass = Class.forName("org.springframework.core.env.ConfigurableEnvironment", true, backendLoader);
        Class<?> standardEnvironment = Class.forName("org.springframework.web.context.support.StandardServletEnvironment", true, backendLoader);
        Class<?> propertySourceClass = Class.forName("org.springframework.core.env.PropertySource", true, backendLoader);
        Class<?> sourcesClass = Class.forName("org.springframework.core.env.MutablePropertySources", true, backendLoader);
        Class<?> mapSourceClass = Class.forName("org.springframework.core.env.MapPropertySource", true, backendLoader);
        Class<?> initializerClass = Class.forName("org.springframework.context.ApplicationContextInitializer", true, backendLoader);

        boundary.put("spring.profiles.active", "local-desktop");
        boundary.put("server.address", "127.0.0.1");
        boundary.put("server.port", "0");
        boundary.put("server.servlet.context-path", "/api");
        boundary.put("app.desktop.data-directory", dataDirectory.toString());
        boundary.put("app.desktop.serve-ui", serveUi);
        // Keep this typed char[]: the backend intentionally rejects a String from env/YAML/CLI.
        boundary.put("app.desktop.database-file-password", secrets.databasePassword);
        boundary.put("app.admin.initial-password", new String(secrets.pendingAdminPassword));
        String apiKey = hasApiKey() ? new String(secrets.apiKey) : "sk-desktop-unconfigured";
        boundary.put("spring.ai.openai.api-key", apiKey);
        boundary.put("deepseek.api-key", apiKey);
        boundary.put("app.desktop.llm-configured", hasApiKey());
        // Override inherited YAML/env/CLI Worker settings even when installation or startup fails.
        boundary.put("app.ai-worker.enabled", false);
        boundary.put("app.ai-worker.base-url", "http://127.0.0.1:1");
        boundary.put("app.ai-worker.shared-secret", "");
        boundary.put("app.ai-worker.desktop-managed", true);
        boundary.put("app.ai-worker.process-alive", (BooleanSupplier) () -> false);
        worker = WorkerSidecar.installed();
        if (worker.start(secrets.apiKey, cancelled)) {
            boundary.put("app.ai-worker.enabled", true);
            boundary.put("app.ai-worker.base-url", worker.baseUri().toString());
            boundary.put("app.ai-worker.shared-secret", worker.sharedSecret());
            boundary.put("app.ai-worker.process-alive", worker.processAlive());
        } else {
            worker = null;
        }

        Object source = mapSourceClass.getConstructor(String.class, Map.class).newInstance(BOUNDARY_SOURCE, boundary);
        Object environment = standardEnvironment.getConstructor().newInstance();
        Object sources = environmentClass.getMethod("getPropertySources").invoke(environment);
        sourcesClass.getMethod("addFirst", propertySourceClass).invoke(sources, source);
        Object application = springClass.getConstructor(Class[].class)
                .newInstance((Object) new Class<?>[]{applicationClass});
        springClass.getMethod("setEnvironment", environmentClass).invoke(application, environment);
        springClass.getMethod("setAdditionalProfiles", String[].class)
                .invoke(application, (Object) new String[]{"local-desktop"});
        springClass.getMethod("setAddCommandLineProperties", boolean.class).invoke(application, false);
        springClass.getMethod("setRegisterShutdownHook", boolean.class).invoke(application, false);
        springClass.getMethod("setLogStartupInfo", boolean.class).invoke(application, false);
        // ConfigData is loaded before initializers. Reassert our boundary after that phase.
        Object initializer = Proxy.newProxyInstance(backendLoader, new Class<?>[]{initializerClass}, (proxy, method, arguments) -> {
            if (method.getName().equals("initialize")) {
                Object configuredEnvironment = Class.forName("org.springframework.context.ConfigurableApplicationContext", true, backendLoader)
                        .getMethod("getEnvironment").invoke(arguments[0]);
                Object configuredSources = environmentClass.getMethod("getPropertySources").invoke(configuredEnvironment);
                sourcesClass.getMethod("addFirst", propertySourceClass).invoke(configuredSources, source);
                return null;
            }
            if (method.getName().equals("toString")) return "DesktopBoundaryInitializer";
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == arguments[0];
            return null;
        });
        Object initializers = Array.newInstance(initializerClass, 1);
        Array.set(initializers, 0, initializer);
        springClass.getMethod("addInitializers", initializers.getClass()).invoke(application, initializers);
        context = springClass.getMethod("run", String[].class).invoke(application, (Object) new String[0]);
        checkCancelled(cancelled);
        Class<?> servletContextClass = Class.forName("org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext", true, backendLoader);
        Object webServer = servletContextClass.getMethod("getWebServer").invoke(context);
        int port = (Integer) Class.forName("org.springframework.boot.web.server.WebServer", true, backendLoader)
                .getMethod("getPort").invoke(webServer);
        ServerAddress address = ServerAddress.parse("http://127.0.0.1:" + port);
        awaitReadiness(address.root().resolve("/api/actuator/health/readiness"), cancelled);
        if (firstRun()) {
            char[] pending = secrets.pendingAdminPassword;
            secrets.pendingAdminPassword = new char[0];
            try {
                store.save(secrets);
                Arrays.fill(pending, '\0');
                boundary.remove("app.admin.initial-password");
            } catch (IOException failure) {
                secrets.pendingAdminPassword = pending;
                throw failure;
            }
        }
        return address;
    }

    private static void awaitReadiness(URI uri, BooleanSupplier cancelled) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            while (System.nanoTime() < deadline) {
                checkCancelled(cancelled);
                try {
                    HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build();
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200 && response.body().matches("(?s).*\"status\"\\s*:\\s*\"UP\".*")) return;
                } catch (IOException transientFailure) {
                    // Boot can bind before all startup tasks become ready. Do not log payloads.
                }
                Thread.sleep(200);
            }
        }
        throw new IOException("本地服务未能按时就绪，请重试。");
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    /** Cold backup may begin only after this returns; credentials and desktop.lock stay owned. */
    void stopBackendKeepingOwnership() throws Exception {
        if (!lock.isValid()) throw new IOException("本地服务已失去数据目录的运行锁。");
        if (context != null) {
            Object previous = context;
            // Worker stays alive until Spring confirms that its business resources are closed.
            backendCloser.closeAndConfirm(previous, backendLoader);
            context = null;
        }
        if (worker != null) {
            // Keep this reference and desktop.lock if even force termination cannot confirm death.
            worker.stopAndConfirm();
            worker = null;
        }
        boundary.clear();
    }

    private static void closeSpringContext(Object context, ClassLoader loader) throws Exception {
        Class<?> contextClass = Class.forName("org.springframework.context.ConfigurableApplicationContext", true, loader);
        contextClass.getMethod("close").invoke(context);
        if ((Boolean) contextClass.getMethod("isActive").invoke(context)) {
            throw new IOException("本地服务未确认关闭，已取消备份。");
        }
    }

    @Override public void close() throws Exception {
        // A failed Spring close still owns live data. Retain both credentials and desktop.lock for retry.
        stopBackendKeepingOwnership();
        boundary.clear();
        secrets.close();
        try { if (lock.isValid()) lock.release(); }
        finally { lockChannel.close(); }
    }
}
