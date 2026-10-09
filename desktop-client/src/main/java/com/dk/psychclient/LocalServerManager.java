package com.dk.psychclient;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** A user-level background server. Closing a client never closes this application's Spring context. */
public final class LocalServerManager extends Application {
    private final ExecutorService lifecycle = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "psych-local-server-lifecycle");
        thread.setDaemon(false);
        return thread;
    });
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicBoolean maintenanceBusy = new AtomicBoolean();
    private final UUID instanceId = UUID.randomUUID();
    private final ClientSettings settings = ClientSettings.forCurrentUser();
    private final LocalServerEndpoint endpoint = LocalServerEndpoint.forDataDirectory(settings.dataDirectory());
    private final LocalDataBackup backups = new LocalDataBackup(settings.dataDirectory());
    private final UserAutoStart autoStart = UserAutoStart.forCurrentUser();
    private final Optional<Path> launcher = UserAutoStart.currentLauncher();
    private Stage stage;
    private VBox content;
    private DesktopTray tray;
    private LocalBackendHost host; // Accessed only on lifecycle's single thread.
    private ServerAddress runningAddress; // Published only after readiness, accessed on lifecycle's thread.
    private ServerAddress address;
    private Thread shutdownHook;
    private boolean background;
    private volatile boolean loginAutoStart = true;
    private String startupNotice = "";

    @Override public void start(Stage primaryStage) {
        stage = primaryStage;
        background = getParameters().getRaw().contains("--background");
        content = new VBox(16);
        content.setPadding(new Insets(28));
        content.setStyle("-fx-background-color: #f7faf8; -fx-font-family: 'Microsoft YaHei'; -fx-font-size: 14px;");
        stage.setTitle("Counseling 本地服务管理");
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: #f7faf8; -fx-background: #f7faf8;");
        stage.setScene(new Scene(scroll, 720, 640));
        stage.setMinWidth(560);
        stage.setMinHeight(360);
        try {
            tray = DesktopTray.install("打开服务管理", "退出服务", "Counseling 本地服务", this::restoreWindow, this::shutdown);
            Platform.setImplicitExit(false);
        } catch (IOException | RuntimeException unavailable) {
            startupNotice = "系统托盘不可用，请保留此管理窗口以维持服务运行。";
        }
        stage.setOnCloseRequest(event -> {
            event.consume();
            if (tray != null) stage.hide();
            else shutdown();
        });
        shutdownHook = new Thread(() -> {
            closing.set(true);
            // Order cleanup behind startup on the same thread; never close Spring concurrently with run().
            try {
                if (!lifecycle.isShutdown()) lifecycle.submit(this::closeBackend).get(30, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Process termination can leave a stale endpoint. Readers still have to probe readiness.
            } finally {
                lifecycle.shutdownNow();
            }
        }, "psych-local-server-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        showLoading("正在准备本地服务…");
        if (!background || tray == null) restoreWindow();
        lifecycle.execute(() -> {
            try {
                // Existing disabled state is kept separately so an intentionally disabled entry stays disabled.
                loginAutoStart = !AutoStartPreference.isDisabled(settings.dataDirectory().getParent());
            } catch (IOException failure) {
                startupNotice = "无法读取登录自启动偏好，可在管理窗口重新设置。";
            }
            prepareAndStart();
        });
    }

    private void prepareAndStart() {
        try {
            if (closing.get()) return;
            if (host == null) host = LocalBackendHost.prepare(settings.dataDirectory());
            else host.stopBackendKeepingOwnership(); // Retry the owned context; never replace it after failed close.
            LocalBackendHost prepared = host;
            if (prepared.firstRun()) {
                onFx(() -> showFirstRun(prepared));
            } else {
                startBackend(prepared);
            }
        } catch (Exception | LinkageError failure) {
            startupFailed(failure);
        }
    }

    private void showFirstRun(LocalBackendHost prepared) {
        Label title = title("保存管理员密码");
        Label explanation = text("管理员账号：admin。请保存下方随机密码，首次启动完成后不再展示；登录后可以修改密码。");
        TextField password = new TextField(prepared.adminPasswordForDisplay());
        password.setEditable(false);
        PasswordField key = new PasswordField();
        key.setPromptText(prepared.hasApiKey() ? "已保存 API Key；留空保留" : "DeepSeek API Key（可选）");
        Label help = text("API Key 由当前 Windows 用户的 DPAPI 加密保存。未配置时可登录和查看记录，配置后才能聊天及联网搜索。");
        CheckBox login = autoStartCheckBox();
        Button start = new Button("密码已保存，启动服务");
        start.setDefaultButton(true);
        start.setOnAction(event -> {
            char[] apiKey = key.getText().strip().toCharArray();
            key.clear();
            password.clear();
            loginAutoStart = login.isSelected();
            showLoading("正在启动服务，首次启动可能需要一些时间…");
            lifecycle.execute(() -> {
                try {
                    if (closing.get()) return;
                    if (apiKey.length != 0) prepared.updateApiKey(apiKey);
                    startBackend(prepared);
                } catch (Exception | LinkageError failure) {
                    startupFailed(failure);
                } finally {
                    Arrays.fill(apiKey, '\0');
                }
            });
        });
        content.getChildren().setAll(title, explanation, password, key, help, login, start);
        restoreWindow(); // Login startup must never silently consume the first-run password.
    }

    private void startBackend(LocalBackendHost prepared) throws Exception {
        if (closing.get()) return;
        ServerAddress ready = prepared.start(closing::get, false);
        if (closing.get()) throw new CancellationException();
        endpoint.publish(ready, instanceId);
        runningAddress = ready;
        String notice = startupNotice;
        try {
            saveAutoStart(loginAutoStart);
        } catch (IOException | IllegalArgumentException failure) {
            notice = "服务已启动，但登录自启动设置未保存。可在管理窗口重新设置。";
        }
        String readyNotice = notice;
        onFx(() -> {
            address = ready;
            showRunning(readyNotice);
            if (background && tray != null) stage.hide();
        });
    }

    private void showRunning(String notice) {
        Label status = title("本地服务正在运行");
        Label description = text("关闭咨询客户端或此管理窗口后，服务会继续在托盘运行。要停止服务，请选择“退出服务”。");
        TextField root = new TextField(address.toString());
        root.setEditable(false);
        Label location = text("数据目录：" + settings.dataDirectory());
        PasswordField apiKey = new PasswordField();
        apiKey.setPromptText("更新 DeepSeek API Key；留空保留现有设置");
        CheckBox clear = new CheckBox("清除已保存的 API Key");
        CheckBox login = autoStartCheckBox();
        Label message = text(notice);
        login.setOnAction(event -> {
            boolean desired = login.isSelected();
            login.setDisable(true);
            lifecycle.execute(() -> {
                try {
                    saveAutoStart(desired);
                    loginAutoStart = desired;
                    onFx(() -> message.setText(desired ? "已启用登录自启动。" : "已关闭登录自启动。"));
                } catch (IOException | IllegalArgumentException failure) {
                    onFx(() -> {
                        login.setSelected(loginAutoStart);
                        message.setText("无法保存登录自启动设置，请检查当前 Windows 用户的权限。");
                    });
                } finally {
                    onFx(() -> login.setDisable(launcher.isEmpty()));
                }
            });
        });
        Button update = new Button("保存 API Key 并重启服务");
        update.setOnAction(event -> {
            char[] supplied = apiKey.getText().strip().toCharArray();
            apiKey.clear();
            boolean replaceKey = clear.isSelected() || supplied.length != 0;
            char[] chosen = clear.isSelected() ? new char[0] : supplied;
            if (!replaceKey) {
                message.setText("请输入新的 API Key，或勾选清除已保存的 API Key。");
                return;
            }
            background = false;
            showLoading("正在保存凭据并重启本地服务…");
            lifecycle.execute(() -> {
                try {
                    if (closing.get()) return;
                    if (host == null) throw new IOException("本地服务未准备完成。");
                    endpoint.removeIfOwned(instanceId);
                    runningAddress = null;
                    host.stopBackendKeepingOwnership();
                    host.updateApiKey(chosen);
                    startBackend(host);
                } catch (Exception | LinkageError failure) {
                    startupFailed(failure);
                } finally {
                    Arrays.fill(supplied, '\0');
                    Arrays.fill(chosen, '\0');
                }
            });
        });
        Button exit = new Button("退出服务");
        exit.setOnAction(event -> shutdown());
        Button backup = new Button("创建加密备份…");
        backup.setOnAction(event -> chooseBackupDestination());
        Button verify = new Button("验证备份（隔离恢复）…");
        verify.setOnAction(event -> chooseBackupForVerification());
        Label backupHelp = text("创建备份会短暂停服，当前聊天连接会中断；完成后自动重启。只创建新文件。"
                + "验证备份只写入隔离目录，适用于同一台电脑、同一 Windows 用户，不会覆盖正式数据。");
        content.getChildren().setAll(status, description, root, location, apiKey, clear, login, message,
                new HBox(12, update, exit), backupHelp, new HBox(12, backup, verify));
    }

    private FileChooser backupChooser(String title) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PsychCounselor 加密备份", "*" + LocalDataBackup.EXTENSION));
        Path documents = Path.of(System.getProperty("user.home"), "Documents");
        if (Files.isDirectory(documents)) chooser.setInitialDirectory(documents.toFile());
        return chooser;
    }

    private void chooseBackupDestination() {
        if (closing.get() || maintenanceBusy.get()) return;
        FileChooser chooser = backupChooser("创建本地加密备份（现有文件不会覆盖）");
        chooser.setInitialFileName("PsychCounselor-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + LocalDataBackup.EXTENSION);
        File selected = chooser.showSaveDialog(stage);
        if (selected == null || closing.get() || !maintenanceBusy.compareAndSet(false, true)) return;
        background = false;
        showLoading("正在暂停服务并创建加密备份…当前聊天连接会暂时中断，完成后自动重启服务。");
        lifecycle.execute(() -> createBackup(selected.toPath()));
    }

    private void createBackup(Path destination) {
        Path created = null;
        Throwable failure = null;
        try {
            if (closing.get()) return;
            if (host == null) throw new IOException("本地服务未准备完成。");
            // Withdraw discovery before stopping. The host keeps desktop.lock through backup and restart.
            endpoint.removeIfOwned(instanceId);
            runningAddress = null;
            host.stopBackendKeepingOwnership();
            created = backups.create(destination, closing::get);
        } catch (Exception | LinkageError problem) {
            failure = problem;
        } finally {
            if (closing.get()) maintenanceBusy.set(false);
            else {
                String notice = created != null ? "加密备份已保存：" + created
                        : "备份未完成，未覆盖已有文件。请检查目标目录权限、可用空间和数据库是否已完全释放。";
                if (failure instanceof CancellationException) notice = "备份已取消。";
                resumeAfterMaintenance(notice);
            }
        }
    }

    private void resumeAfterMaintenance(String notice) {
        try {
            if (closing.get()) return;
            if (host == null) {
                startupNotice = notice;
                maintenanceBusy.set(false);
                prepareAndStart();
                return;
            }
            // Also retries closure after a failed stop/start; a close failure never authorizes copying.
            host.stopBackendKeepingOwnership();
            ServerAddress ready = host.start(closing::get, false);
            if (closing.get()) throw new CancellationException();
            endpoint.publish(ready, instanceId);
            runningAddress = ready;
            onFx(() -> {
                address = ready;
                showRunning(notice + "\n服务已重新就绪；已打开的客户端可重试连接。");
            });
        } catch (Exception | LinkageError failure) {
            runningAddress = null;
            try { endpoint.removeIfOwned(instanceId); }
            catch (IOException ignored) { /* Readers also probe readiness; do not expose native details. */ }
            if (host != null) {
                try { host.stopBackendKeepingOwnership(); }
                catch (Exception ignored) { /* Keep ownership and allow an explicit retry from management. */ }
            }
            if (!closing.get()) onFx(() -> showMaintenanceStopped(notice
                    + "\n服务未恢复运行，请选择重试启动；原 data 和 secrets.dpapi 仍保留。"));
        } finally { maintenanceBusy.set(false); }
    }

    private void chooseBackupForVerification() {
        if (closing.get() || maintenanceBusy.get()) return;
        File selected = backupChooser("选择要验证的本地加密备份").showOpenDialog(stage);
        if (selected == null || closing.get() || !maintenanceBusy.compareAndSet(false, true)) return;
        background = false;
        showLoading("正在认证备份、校验清单并只读验证隔离副本…不会修改正式数据，运行中的服务会继续提供连接。");
        lifecycle.execute(() -> {
            String notice;
            try {
                LocalDataBackup.DrillResult result = backups.restoreDrill(selected.toPath(), closing::get);
                notice = "备份验证通过。备份时间：" + result.backupCreatedAt()
                        + "\n隔离副本：" + result.dataDirectory() + "\n正式数据未被修改，隔离副本没有接入服务。";
            } catch (Exception | LinkageError failure) {
                notice = "备份验证未通过。请确认使用创建备份的同一台 Windows 电脑和同一用户，并检查文件是否损坏或版本不兼容。"
                        + "\n没有覆盖正式数据，也不会生成替代密钥。";
            } finally { maintenanceBusy.set(false); }
            ServerAddress ready = runningAddress;
            String resultNotice = notice;
            onFx(() -> {
                if (ready != null) {
                    address = ready;
                    showRunning(resultNotice);
                } else showMaintenanceStopped(resultNotice);
            });
        });
    }

    private void showMaintenanceStopped(String notice) {
        address = null;
        Button retry = new Button("重试启动服务");
        retry.setOnAction(event -> {
            if (!maintenanceBusy.compareAndSet(false, true)) return;
            showLoading("正在尝试重新启动本地服务…");
            lifecycle.execute(() -> resumeAfterMaintenance(notice));
        });
        Button verify = new Button("验证备份（隔离恢复）…");
        verify.setOnAction(event -> chooseBackupForVerification());
        Button exit = new Button("退出服务");
        exit.setOnAction(event -> shutdown());
        content.getChildren().setAll(title("本地服务暂未就绪"), text(notice), new HBox(12, retry, verify, exit));
        restoreWindow();
    }

    private CheckBox autoStartCheckBox() {
        CheckBox login = new CheckBox("登录 Windows 时在后台启动本地服务");
        login.setSelected(loginAutoStart && launcher.isPresent());
        login.setDisable(launcher.isEmpty());
        if (launcher.isEmpty()) login.setText("登录自启动需要安装后的 PsychCounselorServer.exe");
        return login;
    }

    private void saveAutoStart(boolean enabled) throws IOException {
        if (launcher.isEmpty()) return;
        if (enabled) autoStart.enable(launcher.get());
        else autoStart.disable();
        AutoStartPreference.setDisabled(settings.dataDirectory().getParent(), !enabled);
    }

    private void startupFailed(Throwable failure) {
        boolean stopped = closeBackend();
        if (failure instanceof CancellationException || closing.get()) return;
        String message = failure instanceof ClassNotFoundException
                ? "安装包缺少本地服务组件，请重新安装完整的服务端管理程序。"
                : "本地服务未能启动。请确认没有其他本地服务或旧客户端占用数据目录，并保留原 data 和 secrets.dpapi 文件。";
        if (!stopped) message += "\n旧服务尚未确认停止，仍保留运行锁；重试会先关闭该服务。";
        String failureMessage = message;
        onFx(() -> {
            address = null;
            Button retry = new Button("重试启动");
            retry.setOnAction(event -> {
                showLoading("正在准备本地服务…");
                lifecycle.execute(this::prepareAndStart);
            });
            Button exit = new Button("退出服务");
            exit.setOnAction(event -> shutdown());
            Button verify = new Button("验证备份（隔离恢复）…");
            verify.setOnAction(event -> chooseBackupForVerification());
            content.getChildren().setAll(title("本地服务启动失败"), text(failureMessage), new HBox(12, retry, verify, exit));
            restoreWindow();
        });
    }

    private boolean closeBackend() {
        runningAddress = null;
        // Remove discovery while still holding the host lock; never delete a different instance's endpoint.
        try { endpoint.removeIfOwned(instanceId); }
        catch (IOException ignored) { /* A stale file is checked for readiness by clients. */ }
        host = closeOwnedHost(host);
        return host == null;
    }

    static LocalBackendHost closeOwnedHost(LocalBackendHost owned) {
        if (owned == null) return null;
        try { owned.close(); return null; }
        catch (Exception ignored) {
            // Keep the only reference and its lock so startup retry cannot prepare a second context.
            return owned;
        }
    }

    private void showLoading(String detail) {
        ProgressIndicator progress = new ProgressIndicator();
        progress.setPrefSize(32, 32);
        progress.setMaxSize(32, 32);
        content.getChildren().setAll(title("Counseling 本地服务"), progress, text(detail));
    }

    private Label title(String value) {
        Label label = new Label(value);
        label.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #23483d;");
        return label;
    }

    private Label text(String value) {
        Label label = new Label(value);
        label.setWrapText(true);
        return label;
    }

    private void onFx(Runnable action) {
        Platform.runLater(() -> { if (!closing.get()) action.run(); });
    }

    private void restoreWindow() {
        if (closing.get()) return;
        stage.show();
        stage.setIconified(false);
        stage.toFront();
        stage.requestFocus();
    }

    private void shutdown() {
        if (!closing.compareAndSet(false, true)) return;
        if (tray != null) {
            tray.close();
            tray = null;
        }
        stage.hide();
        lifecycle.execute(() -> {
            try { closeBackend(); }
            finally {
                lifecycle.shutdown();
                try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
                catch (IllegalStateException ignored) { /* JVM shutdown already started. */ }
                Platform.runLater(Platform::exit);
            }
        });
    }

    @Override public void stop() { shutdown(); }
}
