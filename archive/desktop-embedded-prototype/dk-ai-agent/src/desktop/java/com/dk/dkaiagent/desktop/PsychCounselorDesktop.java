package com.dk.dkaiagent.desktop;

import com.dk.dkaiagent.DkAiAgentApplication;
import com.dk.dkaiagent.account.AuthValidation;
import com.dk.dkaiagent.config.DeploymentGuard;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** JavaFX, WebKit, Spring, Tomcat and H2 all live in this JVM. No child-process launcher. */
public final class PsychCounselorDesktop extends Application {
    private final AtomicBoolean closing = new AtomicBoolean();
    private final StackPane root = new StackPane();
    private final Label phase = new Label("正在准备本地工作空间…");
    private final ProgressIndicator progress = new ProgressIndicator();
    private volatile ConfigurableApplicationContext spring;
    private Thread startup;
    private FileChannel lockChannel;
    private FileLock lock;
    private Path dataDirectory;
    private WebEngine engine;
    private Stage window;
    private DesktopSecrets secrets;
    private String apiKey = "";

    @Override
    public void start(Stage stage) {
        Platform.setImplicitExit(false);
        window = stage;
        stage.setTitle("AI 心理咨询师 · PsychCounselor");
        stage.setMinWidth(860);
        stage.setMinHeight(620);
        root.setStyle("-fx-background-color: linear-gradient(to bottom right, #f5f6ff, #f0fbff); -fx-font-family: 'Microsoft YaHei UI';");
        stage.setScene(new Scene(root, 1180, 780));
        showLoading();
        stage.setOnCloseRequest(event -> { event.consume(); requestExit(); });
        stage.show(); // Render the splash before touching Spring, JDBC or model initialization.
        Platform.runLater(() -> {
            try {
                Path userData = defaultDataDirectory();
                Files.createDirectories(userData);
                lockChannel = FileChannel.open(userData.resolve("desktop.lock"),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                try { lock = lockChannel.tryLock(); }
                catch (OverlappingFileLockException ignored) { lock = null; }
                if (lock == null) {
                    showFailure("程序已经在运行", "请使用已打开的 AI 心理咨询师窗口。");
                    return;
                }
                String configured = System.getenv("APP_DATA_DIR");
                dataDirectory = configured == null || configured.isBlank() ? userData : Path.of(configured);
                if (!dataDirectory.isAbsolute()) throw new IllegalArgumentException("APP_DATA_DIR must be absolute");
                dataDirectory = dataDirectory.normalize();
                DesktopDatabaseKey.requireKeyForExistingDatabase(dataDirectory);
                secrets = new DesktopSecrets(dataDirectory);
                try { apiKey = secrets.readApiKey(); }
                catch (Exception unreadableSecrets) {
                    // Do not expose secret contents or require a terminal to repair a moved DPAPI blob.
                    apiKey = "";
                }
                if (!Files.exists(dataDirectory.resolve("counseling.mv.db"))) showFirstRun();
                else if (apiKey.isBlank() && !hasEnvironmentKey()) showApiSetup();
                else startBackend(null);
            } catch (DesktopDatabaseKey.DatabaseKeyException failure) {
                showFailure("无法打开咨询数据库", failure.getMessage());
            } catch (Exception failure) {
                showFailure("无法打开本地工作空间", "请检查用户数据目录的访问权限，然后重新启动程序。");
            }
        });
    }

    private static Path defaultDataDirectory() {
        String local = System.getenv("LOCALAPPDATA");
        return local == null || local.isBlank()
                ? Path.of(System.getProperty("user.home"), ".local", "share", "PsychCounselor")
                : Path.of(local, "PsychCounselor");
    }

    private VBox brandedPanel(String subtitle) {
        Label mark = new Label("心");
        mark.setStyle("-fx-font-size: 42px; -fx-text-fill: #4f46e5;");
        Label title = new Label("AI 心理咨询师");
        title.setStyle("-fx-font-size: 30px; -fx-font-weight: bold; -fx-text-fill: #1e293b;");
        Label description = new Label(subtitle);
        description.setWrapText(true);
        description.setStyle("-fx-font-size: 15px; -fx-text-fill: #64748b;");
        VBox panel = new VBox(20, mark, title, description);
        panel.setAlignment(Pos.CENTER);
        panel.setPadding(new Insets(40));
        panel.setMaxWidth(650);
        panel.setMaxHeight(VBox.USE_PREF_SIZE);
        panel.setStyle("-fx-background-color: rgba(255,255,255,0.94); -fx-background-radius: 24;"
                + "-fx-border-color: #e2e8f0; -fx-border-radius: 24; -fx-border-width: 1;"
                + "-fx-effect: dropshadow(gaussian, rgba(79,70,229,0.08), 24, 0, 0, 8);");
        return panel;
    }

    private void showLoading() {
        VBox panel = brandedPanel("给自己一段安静的时间");
        progress.setPrefSize(44, 44);
        progress.setStyle("-fx-progress-color: #06b6d4;");
        panel.getChildren().addAll(progress, phase);
        root.getChildren().setAll(panel);
    }

    private void showFirstRun() {
        VBox panel = brandedPanel("首次使用：设置本机管理员 admin 的密码");
        PasswordField password = new PasswordField();
        password.setPromptText("密码至少 8 个字符");
        PasswordField confirmation = new PasswordField();
        confirmation.setPromptText("再次输入密码");
        PasswordField keyField = apiKeyField();
        Label notice = new Label("密码仅用于这台电脑上的账号登录。请记住它。");
        notice.setWrapText(true);
        Button begin = new Button("创建本地账号并继续");
        begin.setDefaultButton(true);
        begin.setOnAction(event -> {
            try {
                String value = password.getText();
                AuthValidation.validatePassword(value);
                if (!value.equals(confirmation.getText())) {
                    notice.setText("两次输入的密码不一致。");
                    return;
                }
                if (!saveEnteredKey(keyField, notice)) return;
                password.clear();
                confirmation.clear();
                showLoading();
                startBackend(value);
            } catch (AuthValidation.ValidationException failure) {
                notice.setText("密码至少 8 个字符，UTF-8 编码长度不能超过 72 字节。");
            }
        });
        begin.setStyle("-fx-background-color: #4f46e5; -fx-text-fill: white; -fx-background-radius: 10; -fx-padding: 12 24;");
        panel.getChildren().addAll(password, confirmation, new Label("DeepSeek API Key（可留空稍后配置）"), keyField, notice, begin);
        root.getChildren().setAll(panel);
    }

    private boolean hasEnvironmentKey() {
        String deepseek = System.getenv("DEEPSEEK_API_KEY");
        String openai = System.getenv("SPRING_AI_OPENAI_API_KEY");
        return deepseek != null && !deepseek.isBlank() || openai != null && !openai.isBlank();
    }

    private PasswordField apiKeyField() {
        PasswordField field = new PasswordField();
        field.setPromptText(apiKey.isBlank() ? "可跳过，先进入本地账号" : "已保存；留空沿用现有密钥");
        return field;
    }

    private boolean saveEnteredKey(PasswordField field, Label notice) {
        String entered = field.getText().trim();
        if (entered.isBlank()) return true;
        if (entered.length() > 4096 || entered.indexOf('\n') >= 0 || entered.indexOf('\r') >= 0) {
            notice.setText("API Key 格式不正确，请重新输入。");
            return false;
        }
        try {
            secrets.saveApiKey(entered);
            apiKey = entered;
            field.clear();
            return true;
        } catch (Exception failure) {
            notice.setText("无法保存密钥，请检查数据目录权限。更换 Windows 账号后需要重新输入。");
            return false;
        }
    }

    private void showApiSetup() {
        VBox panel = brandedPanel("配置模型服务，也可以先进入本地账号");
        PasswordField field = apiKeyField();
        Label notice = new Label("API Key 使用 Windows 用户级加密保存。留空可跳过；咨询模型服务需要有效密钥。");
        notice.setWrapText(true);
        Button proceed = new Button("继续");
        proceed.setDefaultButton(true);
        proceed.setStyle("-fx-background-color: #4f46e5; -fx-text-fill: white; -fx-background-radius: 10; -fx-padding: 12 24;");
        proceed.setOnAction(event -> {
            if (saveEnteredKey(field, notice)) { showLoading(); startBackend(null); }
        });
        panel.getChildren().addAll(field, notice, proceed);
        root.getChildren().setAll(panel);
    }

    private void startBackend(String initialPassword) {
        startup = new Thread(() -> {
            Map<String, Object> properties = new ConcurrentHashMap<>();
            try {
                properties.put("app.desktop.database-file-password", DesktopDatabaseKey.readOrCreate(dataDirectory));
                SpringApplication application = new SpringApplication(DkAiAgentApplication.class);
                application.setRegisterShutdownHook(false); // The JavaFX lifecycle owns context.close().
                application.setAddCommandLineProperties(false);
                application.addListeners(new DeploymentGuard(DeploymentGuard.validateEnvironment(System.getenv())));
                properties.put("spring.profiles.active", "desktop");
                properties.put("server.address", "127.0.0.1");
                properties.put("server.port", "0");
                properties.put("server.servlet.context-path", "/api");
                properties.put("server.servlet.session.cookie.secure", "false");
                properties.put("server.shutdown", "graceful");
                properties.put("app.ai-worker.enabled", "false");
                properties.put("spring.ai.mcp.client.enabled", "false");
                properties.put("app.desktop.data-directory", dataDirectory.toString());
                properties.put("spring.main.banner-mode", "off");
                properties.put("app.cors.allowed-origin-patterns", "");
                if (!apiKey.isBlank()) {
                    properties.put("deepseek.api-key", apiKey);
                    properties.put("spring.ai.openai.api-key", apiKey);
                }
                String install = System.getProperty("psych.desktop.install-dir");
                if (install != null && !install.isBlank()) {
                    properties.put("app.rag.transcript-directory", Path.of(install, "counseling-kb", "raw").toString());
                    Path model = Path.of(install, "models", "model.onnx");
                    Path tokenizer = Path.of(install, "models", "tokenizer.json");
                    if (Files.isRegularFile(model) && Files.isRegularFile(tokenizer)) {
                        properties.put("spring.ai.embedding.transformer.onnx.model-uri", model.toUri().toString());
                        properties.put("spring.ai.embedding.transformer.tokenizer.uri", tokenizer.toUri().toString());
                    }
                }
                if (initialPassword != null) properties.put("app.admin.initial-password", initialPassword);
                // Profiles must be selected before ConfigData resolves application-desktop.yml.
                application.setDefaultProperties(Map.of("spring.profiles.active", "desktop"));
                System.setProperty("spring.profiles.active", "desktop");
                application.addListeners(event -> {
                    if (event instanceof ApplicationEnvironmentPreparedEvent prepared) {
                        prepared.getEnvironment().getPropertySources().addFirst(
                                new MapPropertySource("desktopProcessBoundary", properties));
                        updatePhase("正在连接本地存储、初始化咨询服务…");
                    } else if (event instanceof ApplicationPreparedEvent) {
                        updatePhase("正在准备本地账号与知识库…");
                    } else if (event instanceof ApplicationStartedEvent) {
                        updatePhase("正在加载知识库；首次使用可能需要较长时间…");
                    } else if (event instanceof ApplicationReadyEvent) {
                        updatePhase("服务已就绪，正在打开登录页面…");
                    }
                });
                ConfigurableApplicationContext context = application.run();
                spring = context;
                // Drop bootstrap credentials once beans have consumed them; the pool owns its copy.
                clearDatabasePassword(properties);
                properties.remove("app.admin.initial-password");
                if (closing.get()) return;
                int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
                URI origin = URI.create("http://127.0.0.1:" + port);
                awaitReady(origin);
                if (!closing.get()) Platform.runLater(() -> openLogin(origin));
            } catch (Throwable failure) {
                boolean needsAdmin = false;
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    if (cause instanceof IllegalStateException && cause.getMessage() != null
                            && cause.getMessage().contains("ADMIN_INITIAL_PASSWORD")) needsAdmin = true;
                }
                boolean promptForAdmin = needsAdmin;
                if (!closing.get()) Platform.runLater(() -> {
                    // A failed first bootstrap may have already created H2's file, without an admin row.
                    if (failure instanceof DesktopDatabaseKey.DatabaseKeyException) {
                        showFailure("无法打开咨询数据库", failure.getMessage());
                    } else if (promptForAdmin) showFirstRun();
                    else showFailure("启动未完成", "本地存储或咨询服务初始化失败（"
                            + failure.getClass().getSimpleName()
                            + "）。请检查安装资源、网络和本地配置后重新启动。");
                });
            } finally {
                clearDatabasePassword(properties);
                properties.remove("app.admin.initial-password");
            }
        }, "desktop-spring-startup");
        startup.start();
    }

    private static void clearDatabasePassword(Map<String, Object> properties) {
        Object password = properties.remove("app.desktop.database-file-password");
        if (password instanceof char[] value) Arrays.fill(value, '\0');
    }

    private void awaitReady(URI origin) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            HttpRequest health = HttpRequest.newBuilder(origin.resolve("/api/actuator/health/readiness"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (!closing.get() && System.nanoTime() < deadline) {
                try {
                    HttpResponse<String> result = client.send(health, HttpResponse.BodyHandlers.ofString());
                    if (result.statusCode() == 200 && result.body().contains("\"UP\"")) return;
                } catch (java.io.IOException ignored) { }
                Thread.sleep(250);
            }
            if (!closing.get()) throw new IllegalStateException("Local readiness timed out");
        }
    }

    private void openLogin(URI origin) {
        if (closing.get()) return;
        try {
            WebView view = new WebView();
            view.setContextMenuEnabled(false);
            engine = view.getEngine();
            engine.setUserDataDirectory(dataDirectory.resolve("webview").toFile());
            engine.setCreatePopupHandler(features -> null);
            engine.locationProperty().addListener((observable, previous, location) -> {
                if (location == null || location.isBlank() || "about:blank".equals(location)) return;
                try {
                    URI destination = URI.create(location);
                    if (!"http".equals(destination.getScheme()) || !"127.0.0.1".equals(destination.getHost())
                            || destination.getPort() != origin.getPort()) {
                        engine.getLoadWorker().cancel();
                        showFailure("链接无法在客户端内打开", "请重新启动客户端回到登录页面。");
                    }
                } catch (IllegalArgumentException failure) { engine.getLoadWorker().cancel(); }
            });
            engine.getLoadWorker().stateProperty().addListener((observable, previous, state) -> {
                if (closing.get()) return;
                if (state == Worker.State.SUCCEEDED) root.getChildren().setAll(view);
                else if (state == Worker.State.FAILED) showFailure("登录页面加载失败", "安装中的前端资源无法加载，请重新安装后再试。");
            });
            engine.setOnError(event -> showFailure("页面组件发生错误", "请退出后重新启动客户端。"));
            engine.load(origin.resolve("/login").toString()); // Top-level same-origin navigation; no Java/JS bridge.
        } catch (Throwable failure) {
            showFailure("无法初始化页面组件", "请确认安装包包含完整 JavaFX 运行时，并使用受支持的 Windows 系统。");
        }
    }

    private void updatePhase(String value) {
        if (!closing.get()) Platform.runLater(() -> { if (!closing.get()) phase.setText(value); });
    }

    private void showFailure(String title, String detail) {
        if (closing.get()) return;
        VBox panel = brandedPanel(title);
        Label message = new Label(detail);
        message.setWrapText(true);
        Button exit = new Button("退出");
        exit.setOnAction(event -> requestExit());
        panel.getChildren().addAll(message, exit);
        root.getChildren().setAll(panel);
    }

    private void requestExit() {
        if (!closing.compareAndSet(false, true)) return;
        if (engine != null) {
            engine.getLoadWorker().cancel();
            engine.load("about:blank"); // Tear down the page and its SSE requests before graceful Tomcat shutdown.
        }
        phase.setText("正在保存本地数据并退出…");
        showLoading();
        Thread shutdown = new Thread(() -> {
            try {
                // Closing during bootstrap must wait for run() to return, then close its eventual context.
                if (startup != null) {
                    startup.interrupt();
                    startup.join();
                }
                ConfigurableApplicationContext context = spring;
                if (context != null) context.close();
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
            } finally {
                releaseLock();
                Platform.runLater(() -> { window.hide(); Platform.exit(); });
            }
        }, "desktop-graceful-shutdown");
        shutdown.start();
    }

    private void releaseLock() {
        try { if (lock != null && lock.isValid()) lock.release(); } catch (java.io.IOException ignored) { }
        try { if (lockChannel != null) lockChannel.close(); } catch (java.io.IOException ignored) { }
        // Keep the lock file: deleting a live lock path creates races; OS releases the lock after crashes.
    }

    @Override
    public void stop() {
        ConfigurableApplicationContext context = spring;
        if (context != null && context.isActive()) context.close();
        releaseLock();
    }
}
