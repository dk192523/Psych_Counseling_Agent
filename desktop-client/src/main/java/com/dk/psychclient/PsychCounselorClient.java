package com.dk.psychclient;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.*;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import netscape.javascript.JSObject;

import java.io.IOException;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Independent WebView client; backend ownership belongs to the server manager. */
public final class PsychCounselorClient extends Application {
    private final boolean remoteOnly = Boolean.getBoolean("psychclient.remote-only");
    private final ClientSettings settings = ClientSettings.forCurrentUser();
    private final ExecutorService lifecycle = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "desktop-client-lifecycle");
        thread.setDaemon(false); // Finish closing this client's proxy before exiting.
        return thread;
    });
    private final AtomicLong generation = new AtomicLong();
    private final BorderPane window = new BorderPane();
    private final StackPane content = new StackPane();
    private final PauseTransition timeout = new PauseTransition(Duration.seconds(30));
    private ClientSettings.Connection selected = ClientSettings.Connection.local();
    private WebEngine engine;
    // JavaFX keeps JS bridge references weakly; retain it for the active document.
    private DesktopPageBridge pageBridge;
    private Stage stage;
    private volatile boolean closing;
    private volatile ClientUiHost uiHost;

    @Override public void start(Stage stage) {
        this.stage = stage;
        Platform.setImplicitExit(false);
        stage.setTitle("Counseling");
        URL logo = PsychCounselorClient.class.getResource("/icons/logo.png");
        if (logo != null) stage.getIcons().add(new Image(logo.toExternalForm()));
        stage.setMinWidth(760);
        stage.setMinHeight(560);
        window.setStyle("-fx-background-color: #f2f7fe; -fx-font-family: 'Microsoft YaHei', 'Segoe UI';"
                + "-fx-font-size: 13px; -fx-accent: #2563eb; -fx-focus-color: #2563eb;"
                + "-fx-faint-focus-color: rgba(37, 99, 235, 0.14);");
        window.setCenter(content);
        showLoading("正在读取连接设置…");
        Scene scene = new Scene(window, 1160, 780);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.COMMA, KeyCombination.CONTROL_DOWN),
                () -> showSettings(""));
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> {
            event.consume();
            shutdown();
        });
        stage.show();
        long token = generation.incrementAndGet();
        lifecycle.execute(() -> {
            try {
                ClientSettings.Connection loaded = settings.loadConnection();
                onFx(token, () -> { selected = loaded; connect(); });
            } catch (IOException | IllegalArgumentException failure) {
                onFx(token, () -> showSettings("无法读取连接配置，请重新选择连接模式。"));
            }
        });
    }

    private VBox setupPanel(String heading) {
        Label title = new Label(heading);
        title.setWrapText(true);
        title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #1f2937;");
        VBox panel = new VBox(16, title);
        panel.setAlignment(Pos.CENTER_LEFT);
        panel.setPadding(new Insets(32));
        panel.setMaxWidth(500);
        panel.setMaxHeight(Region.USE_PREF_SIZE);
        panel.setStyle("-fx-background-color: rgba(255, 255, 255, 0.78); -fx-background-radius: 20;"
                + "-fx-border-color: rgba(15, 23, 42, 0.08); -fx-border-radius: 20;"
                + "-fx-effect: dropshadow(gaussian, rgba(15, 23, 42, 0.10), 28, 0, 0, 8);");
        return panel;
    }

    private void showSetupPanel(VBox panel) {
        StackPane frame = new StackPane(panel);
        frame.setPadding(new Insets(24));
        ScrollPane scroll = new ScrollPane(frame);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent; -fx-border-width: 0;");
        content.getChildren().setAll(scroll);
    }

    private Button actionButton(String text, boolean primary) {
        Button button = new Button(text);
        button.setStyle("-fx-background-color: "
                + (primary ? "linear-gradient(to bottom right, #2563eb, #0ea5e9)" : "#e8f0fe")
                + "; -fx-text-fill: " + (primary ? "white" : "#2563eb")
                + "; -fx-background-radius: 24; -fx-padding: 11 20; -fx-font-weight: bold; -fx-cursor: hand;");
        return button;
    }

    private void styleField(TextField field) {
        field.setStyle("-fx-background-color: rgba(255, 255, 255, 0.75); -fx-background-radius: 12;"
                + "-fx-border-color: #d5deea; -fx-border-radius: 12; -fx-padding: 11 12;"
                + "-fx-text-fill: #1f2937; -fx-prompt-text-fill: #9aa7ba;");
    }

    private void showLoading(String detail) {
        ProgressIndicator progress = new ProgressIndicator();
        progress.setPrefSize(28, 28);
        progress.setMaxSize(28, 28);
        Label status = new Label(detail);
        status.setWrapText(true);
        status.setStyle("-fx-text-fill: #6b7280;");
        VBox panel = new VBox(16, progress, status);
        panel.setAlignment(Pos.CENTER);
        panel.setPadding(new Insets(24));
        content.getChildren().setAll(panel);
    }

    private void showSettings(String warning) {
        if (closing) return;
        long token = generation.incrementAndGet();
        discardPage();
        VBox panel = setupPanel("连接设置");
        ComboBox<String> mode = new ComboBox<>();
        if (!remoteOnly) mode.getItems().add("本地模式");
        mode.getItems().add("远程服务");
        mode.getSelectionModel().select(!remoteOnly && selected.mode() == ClientSettings.Mode.LOCAL ? "本地模式" : "远程服务");
        mode.setMaxWidth(Double.MAX_VALUE);
        mode.setStyle("-fx-background-color: #e8f0fe; -fx-background-radius: 12; -fx-padding: 7;");
        TextField address = new TextField(selected.remote() == null ? "" : selected.remote().toString());
        styleField(address);
        address.setPromptText("远程服务端根 URL，例如 https://counsel.example.com");
        Label help = new Label();
        help.setWrapText(true);
        help.setStyle("-fx-text-fill: #6b7280;");
        Runnable updateFields = () -> {
            boolean local = !remoteOnly && "本地模式".equals(mode.getValue());
            address.setVisible(!local);
            address.setManaged(!local);
            help.setText(local
                    ? "连接当前 Windows 用户的本地服务。请先启动服务端或服务托盘；模型密钥和管理员初始化由服务端管理。"
                    : "填写根地址，不附加 /login 或 /api。远程服务必须使用 HTTPS；本机回环地址可使用 HTTP。");
        };
        mode.valueProperty().addListener((observable, oldValue, newValue) -> updateFields.run());
        updateFields.run();
        Label notice = new Label(warning);
        notice.setWrapText(true);
        notice.setStyle("-fx-text-fill: #b45309;");
        Button save = actionButton("保存并连接", true);
        save.setDefaultButton(true);
        save.setOnAction(event -> {
            ClientSettings.Connection next;
            try {
                next = !remoteOnly && "本地模式".equals(mode.getValue()) ? ClientSettings.Connection.local()
                        : new ClientSettings.Connection(ClientSettings.Mode.REMOTE, ServerAddress.parse(address.getText()));
            } catch (IllegalArgumentException invalid) {
                notice.setText(invalid.getMessage());
                return;
            }
            showLoading("正在保存连接设置…");
            lifecycle.execute(() -> {
                try {
                    if (cancelled(token)) return;
                    settings.saveConnection(next);
                    onFx(token, () -> { selected = next; connect(); });
                } catch (IOException failure) {
                    onFx(token, () -> showSettings("无法保存连接设置，请检查当前用户的目录权限。"));
                }
            });
        });
        Button cancel = actionButton("返回", false);
        cancel.setOnAction(event -> connect());
        HBox actions = new HBox(12, save, cancel);
        actions.setAlignment(Pos.CENTER_RIGHT);
        panel.getChildren().addAll(mode, address, help, notice, actions);
        showSetupPanel(panel);
    }

    private void connect() {
        if (closing) return;
        if (remoteOnly && selected.mode() == ClientSettings.Mode.LOCAL) {
            showSettings("此发行包仅连接远程服务，请填写服务端根地址。");
            return;
        }
        long token = generation.incrementAndGet();
        discardPage();
        ClientSettings.Connection desired = selected;
        showLoading(desired.mode() == ClientSettings.Mode.LOCAL
                ? "正在连接本地服务…" : "正在连接远程服务…");
        lifecycle.execute(() -> {
            ClientUiHost candidate = null;
            try {
                if (cancelled(token)) return;
                ServerAddress upstream;
                if (desired.mode() == ClientSettings.Mode.LOCAL) {
                    var endpoint = LocalServerEndpoint.forDataDirectory(settings.dataDirectory()).read();
                    if (endpoint.isEmpty()) {
                        onFx(token, () -> fail("未发现本地服务。请启动服务端或服务托盘，等待服务就绪后重试。"));
                        return;
                    }
                    upstream = endpoint.get().address();
                } else upstream = desired.remote();
                candidate = new ClientUiHost(upstream);
                uiHost = candidate;
                if (cancelled(token)) { candidate.close(); return; }
                candidate.checkUpstreamHealth();
                if (cancelled(token)) { candidate.close(); return; }
                candidate.start();
                ClientUiHost ready = candidate;
                onFx(token, () -> loadPage(ready));
                if (cancelled(token)) candidate.close();
            } catch (ClientUiHost.ProtocolMismatchException | LocalServerEndpoint.ProtocolMismatchException failure) {
                if (candidate != null) candidate.close();
                onFx(token, () -> fail("客户端与服务端协议版本不兼容。请更新客户端与服务端后重试。"));
            } catch (IOException | RuntimeException | LinkageError failure) {
                if (candidate != null) candidate.close();
                // Never expose upstream errors, URLs with capabilities, or configuration values.
                String message = desired.mode() == ClientSettings.Mode.LOCAL
                        ? "本地服务不可用。请启动或检查服务端/服务托盘，等待服务就绪后重试。"
                        : "远程服务不可用。请检查服务地址、网络、HTTPS 证书及服务运行状态后重试。";
                onFx(token, () -> fail(message));
            }
        });
    }

    private void loadPage(ClientUiHost host) {
        if (closing || uiHost != host) { host.close(); return; }
        showLoading("正在加载咨询页面…");
        try {
            WebView view = new WebView();
            view.setContextMenuEnabled(false);
            WebEngine page = view.getEngine();
            engine = page;
            ServerAddress allowed = ServerAddress.parse(host.origin().toASCIIString());
            // JavaFX may keep loading subresources after the main document succeeds.
            boolean[] documentLoaded = {false};
            page.setCreatePopupHandler(features -> null);
            page.setConfirmHandler(message -> false);
            page.setPromptHandler(prompt -> null);
            page.setOnAlert(event -> { });
            page.locationProperty().addListener((observable, previous, destination) -> {
                if (closing || engine != page) return;
                if (!allowed.allowsNavigation(destination)) fail("已拦截其他站点的跳转，请重新连接或修改连接设置。");
            });
            page.getLoadWorker().stateProperty().addListener((observable, previous, state) -> {
                if (closing || engine != page) return;
                if (documentLoaded[0]) {
                    if (state != Worker.State.SCHEDULED && state != Worker.State.RUNNING) return;
                    try {
                        if (!"loading".equals(page.executeScript("document.readyState"))) return;
                    } catch (RuntimeException ignored) {
                        return;
                    }
                    documentLoaded[0] = false;
                }
                if (state == Worker.State.SCHEDULED || state == Worker.State.RUNNING) {
                    showLoading("正在加载咨询页面…");
                    timeout.playFromStart();
                } else if (state == Worker.State.SUCCEEDED) {
                    timeout.stop();
                    if (!allowed.allowsNavigation(page.getLocation())) fail("已拦截其他站点的页面。");
                    else {
                        documentLoaded[0] = true;
                        pageBridge = new DesktopPageBridge(
                                () -> !closing && engine == page && allowed.allowsNavigation(page.getLocation()),
                                uri -> getHostServices().showDocument(uri.toASCIIString()),
                                name -> {
                                    FileChooser chooser = new FileChooser();
                                    chooser.setTitle("导出会话 Markdown");
                                    chooser.setInitialFileName(name);
                                    chooser.getExtensionFilters().add(
                                            new FileChooser.ExtensionFilter("Markdown 文件 (*.md)", "*.md"));
                                    java.io.File chosen = chooser.showSaveDialog(stage);
                                    return chosen == null ? null : chosen.toPath();
                                });
                        ((JSObject) page.executeScript("window")).setMember("psychDesktop", pageBridge);
                        content.getChildren().setAll(view);
                    }
                } else if (state == Worker.State.FAILED) {
                    fail("页面加载失败，请检查服务状态、网络及 HTTPS 证书后重试。");
                }
            });
            page.setOnError(event -> {
                if (engine == page && !closing && !documentLoaded[0]) fail("网页组件发生错误，请重试连接。");
            });
            timeout.setOnFinished(event -> {
                if (engine == page && !closing && !documentLoaded[0]) fail("页面加载超时，请重试或检查连接设置。");
            });
            timeout.playFromStart();
            // Redeem the one-time capability before the bundled UI sends API requests.
            page.load(host.bootstrapUri().toASCIIString());
        } catch (RuntimeException | LinkageError failure) {
            fail("无法启动网页组件，请确认安装目录完整，并使用受支持的 Windows 系统。");
        }
    }

    private void fail(String message) {
        if (closing) return;
        discardPage();
        VBox panel = setupPanel("暂时无法连接");
        Label detail = new Label(message);
        detail.setWrapText(true);
        detail.setStyle("-fx-text-fill: #6b7280;");
        Button retry = actionButton("重试", true);
        retry.setOnAction(event -> connect());
        Button configure = actionButton("连接设置", false);
        configure.setOnAction(event -> showSettings(""));
        HBox actions = new HBox(12, retry, configure);
        actions.setAlignment(Pos.CENTER_RIGHT);
        panel.getChildren().addAll(detail, actions);
        showSetupPanel(panel);
    }

    private void discardPage() {
        timeout.stop();
        WebEngine previous = engine;
        engine = null;
        pageBridge = null;
        if (previous != null) {
            previous.getLoadWorker().cancel();
            previous.load(null); // Retire the document, terminating fetch/EventSource/SSE first.
        }
        closeUiHost();
    }

    private boolean cancelled(long token) { return closing || generation.get() != token; }

    private void onFx(long token, Runnable action) {
        Platform.runLater(() -> { if (!cancelled(token)) action.run(); });
    }

    private void closeUiHost() {
        ClientUiHost previous = uiHost;
        uiHost = null;
        if (previous != null) previous.close();
    }

    private void shutdown() {
        if (closing) return;
        closing = true;
        generation.incrementAndGet();
        discardPage();
        if (stage != null) stage.hide();
        lifecycle.execute(() -> {
            try { closeUiHost(); }
            finally {
                lifecycle.shutdown();
                Platform.runLater(Platform::exit);
            }
        });
    }

    @Override public void stop() { shutdown(); }
}
