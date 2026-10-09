using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace DkPsychCounselorLauncher
{
    internal static class Program
    {
        [STAThread]
        private static int Main(string[] args)
        {
            if (HasArgument(args, "--self-test"))
            {
                return SelfTest.Run();
            }

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new LauncherForm(HasArgument(args, "--preview-ui")));
            return 0;
        }

        private static bool HasArgument(IEnumerable<string> args, string expected)
        {
            foreach (string arg in args)
            {
                if (string.Equals(arg, expected, StringComparison.OrdinalIgnoreCase))
                {
                    return true;
                }
            }

            return false;
        }
    }

    internal static class SelfTest
    {
        internal static int Run()
        {
            try
            {
                LauncherPaths paths = LauncherPaths.Discover();
                if (!Directory.Exists(paths.ProjectRoot))
                {
                    return 10;
                }

                if (!File.Exists(paths.ComposeFile))
                {
                    return 11;
                }

                if (string.IsNullOrEmpty(paths.DockerCli))
                {
                    return 12;
                }

                if (string.IsNullOrEmpty(paths.DockerDesktop))
                {
                    return 13;
                }

                if (string.IsNullOrEmpty(paths.BrowserExecutable))
                {
                    return 14;
                }

                // Intentionally do not fail when the secret is absent. The real launch
                // validates it, while a source/build integrity check must not require a key.
                ApiKeyResolver.Resolve(paths.BackendDirectory);

                TcpListener occupiedPort = null;
                try
                {
                    int fallbackPort = PortInspector.FindAlternativePort();
                    if (fallbackPort < 3002 || fallbackPort > 3010)
                    {
                        return 15;
                    }

                    occupiedPort = new TcpListener(IPAddress.Loopback, fallbackPort);
                    occupiedPort.Server.ExclusiveAddressUse = true;
                    occupiedPort.Start();
                    if (!PortInspector.Inspect(fallbackPort).IsOccupied)
                    {
                        return 16;
                    }
                }
                finally
                {
                    if (occupiedPort != null)
                    {
                        occupiedPort.Stop();
                    }
                }

                return 0;
            }
            catch
            {
                return 20;
            }
        }
    }

    internal sealed class LauncherForm : Form
    {
        private const string ComposeProjectName = "psych-counseling-agent";

        private readonly Label _statusLabel;
        private readonly RichTextBox _logBox;
        private readonly ActivityBar _progressBar;
        private readonly Button _stopButton;
        private readonly Label _stateBadge;
        private readonly Label _stageLabel;
        private readonly Label _detailLabel;
        private readonly Label _latestLogLabel;
        private readonly Label[] _stepLabels;
        private readonly Panel _dashboardPage;
        private readonly Panel _logsPage;
        private readonly DashboardButton _dashboardButton;
        private readonly DashboardButton _logsButton;
        private readonly CancellationTokenSource _cancellation = new CancellationTokenSource();
        private readonly object _processLock = new object();
        private readonly object _logFileLock = new object();
        private readonly string _logFilePath;

        private LauncherPaths _paths;
        private KeyMaterial _keyMaterial;
        private Process _commandProcess;
        private Process _browserProcess;
        private string _browserProfileDirectory;
        private bool _composeWasAttempted;
        private bool _allowClose;
        private int _shutdownStarted;
        private int _frontendPort = 3001;
        private int _activeStep;

        private string HealthUrl
        {
            get { return "http://127.0.0.1:" + _frontendPort + "/api/health"; }
        }

        private string AppUrl
        {
            get { return "http://127.0.0.1:" + _frontendPort + "/psych-master"; }
        }

        internal LauncherForm(bool previewUi)
        {
            _logFilePath = previewUi ? null : InitializeLogFile();
            _allowClose = previewUi;
            Text = "AI 心理咨询师 · 本地工作台";
            StartPosition = FormStartPosition.CenterScreen;
            AutoScaleDimensions = new SizeF(96F, 96F);
            AutoScaleMode = AutoScaleMode.Dpi;
            ClientSize = new Size(1040, 700);
            MinimumSize = new Size(900, 620);
            BackColor = LauncherTheme.Canvas;
            ForeColor = LauncherTheme.Ink;
            Font = new Font("Microsoft YaHei UI", 9F, FontStyle.Regular, GraphicsUnit.Point);
            Padding = new Padding(12);
            DoubleBuffered = true;

            TableLayoutPanel shell = NewTable(2, 1);
            shell.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 208F));
            shell.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100F));
            shell.RowStyles.Add(new RowStyle(SizeType.Percent, 100F));
            Controls.Add(shell);

            RoundedPanel sidebar = new RoundedPanel();
            sidebar.Dock = DockStyle.Fill;
            sidebar.Padding = new Padding(16, 20, 16, 20);
            sidebar.Margin = new Padding(0);
            shell.Controls.Add(sidebar, 0, 0);
            TableLayoutPanel navigation = NewTable(1, 7);
            navigation.RowStyles.Add(new RowStyle(SizeType.Absolute, 108F));
            navigation.RowStyles.Add(new RowStyle(SizeType.Absolute, 30F));
            navigation.RowStyles.Add(new RowStyle(SizeType.Absolute, 48F));
            navigation.RowStyles.Add(new RowStyle(SizeType.Absolute, 48F));
            navigation.RowStyles.Add(new RowStyle(SizeType.Percent, 100F));
            navigation.RowStyles.Add(new RowStyle(SizeType.Absolute, 24F));
            navigation.RowStyles.Add(new RowStyle(SizeType.Absolute, 74F));
            sidebar.Controls.Add(navigation);

            Panel brand = new Panel();
            brand.Dock = DockStyle.Fill;
            brand.Margin = new Padding(0);
            RoundedPanel mark = new RoundedPanel();
            mark.SurfaceColor = LauncherTheme.PrimarySoft;
            mark.BorderColor = LauncherTheme.Primary;
            mark.Size = new Size(44, 44);
            Label markText = NewLabel("心", 17F, FontStyle.Bold, LauncherTheme.Accent);
            markText.TextAlign = ContentAlignment.MiddleCenter;
            mark.Controls.Add(markText);
            brand.Controls.Add(mark);
            Label brandName = NewLabel("心理咨询师", 13F, FontStyle.Bold, LauncherTheme.Ink);
            brandName.Dock = DockStyle.None;
            brandName.SetBounds(0, 53, 176, 26);
            brand.Controls.Add(brandName);
            Label brandCaption = NewLabel("AI COUNSELOR", 8F, FontStyle.Regular, LauncherTheme.Muted);
            brandCaption.Dock = DockStyle.None;
            brandCaption.SetBounds(0, 82, 176, 20);
            brand.Controls.Add(brandCaption);
            navigation.Controls.Add(brand, 0, 0);
            navigation.Controls.Add(NewLabel("本地工作空间", 8.5F, FontStyle.Regular, LauncherTheme.Muted), 0, 1);
            _dashboardButton = NewButton("01    工作台", LauncherTheme.PrimarySoft);
            _dashboardButton.TextAlign = ContentAlignment.MiddleLeft;
            _dashboardButton.Click += delegate { ShowPage(false); };
            navigation.Controls.Add(_dashboardButton, 0, 2);
            _logsButton = NewButton("02    运行日志", LauncherTheme.Surface);
            _logsButton.TextAlign = ContentAlignment.MiddleLeft;
            _logsButton.Click += delegate { ShowPage(true); };
            navigation.Controls.Add(_logsButton, 0, 3);
            navigation.Controls.Add(NewLabel("LOCAL SESSION", 8F, FontStyle.Bold, LauncherTheme.Accent), 0, 5);
            navigation.Controls.Add(NewLabel("独立网页窗口\n关闭时停止本项目服务\n保留历史会话与模型缓存", 8.5F, FontStyle.Regular, LauncherTheme.Muted), 0, 6);

            TableLayoutPanel workspace = NewTable(1, 3);
            workspace.Padding = new Padding(28, 8, 4, 0);
            workspace.RowStyles.Add(new RowStyle(SizeType.Absolute, 88F));
            workspace.RowStyles.Add(new RowStyle(SizeType.Percent, 100F));
            workspace.RowStyles.Add(new RowStyle(SizeType.Absolute, 86F));
            shell.Controls.Add(workspace, 1, 0);
            TableLayoutPanel heading = NewTable(2, 2);
            heading.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100F));
            heading.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 140F));
            heading.RowStyles.Add(new RowStyle(SizeType.Absolute, 43F));
            heading.RowStyles.Add(new RowStyle(SizeType.Percent, 100F));
            heading.Controls.Add(NewLabel("AI 心理咨询师", 23F, FontStyle.Bold, LauncherTheme.Ink), 0, 0);
            _stateBadge = NewLabel("正在启动", 9F, FontStyle.Bold, LauncherTheme.Accent);
            _stateBadge.TextAlign = ContentAlignment.MiddleCenter;
            RoundedPanel badgePanel = new RoundedPanel();
            badgePanel.SurfaceColor = LauncherTheme.Subtle;
            badgePanel.Dock = DockStyle.Fill;
            badgePanel.Margin = new Padding(8, 6, 0, 6);
            badgePanel.Controls.Add(_stateBadge);
            heading.Controls.Add(badgePanel, 1, 0);
            Label subtitle = NewLabel("启动本地服务，准备好后自动进入咨询网页。", 9.5F, FontStyle.Regular, LauncherTheme.Muted);
            heading.Controls.Add(subtitle, 0, 1);
            heading.SetColumnSpan(subtitle, 2);
            workspace.Controls.Add(heading, 0, 0);

            Panel pageHost = new Panel();
            pageHost.Dock = DockStyle.Fill;
            pageHost.Margin = new Padding(0);
            workspace.Controls.Add(pageHost, 0, 1);
            _dashboardPage = new Panel();
            _dashboardPage.Dock = DockStyle.Fill;
            _dashboardPage.AutoScroll = true;
            pageHost.Controls.Add(_dashboardPage);
            TableLayoutPanel dashboard = NewTable(1, 2);
            dashboard.Dock = DockStyle.Top;
            dashboard.MinimumSize = new Size(0, 476);
            dashboard.Height = 476;
            dashboard.RowStyles.Add(new RowStyle(SizeType.Percent, 100F));
            dashboard.RowStyles.Add(new RowStyle(SizeType.Absolute, 136F));
            _dashboardPage.Controls.Add(dashboard);
            _dashboardPage.Resize += delegate
            {
                dashboard.Height = Math.Max(dashboard.MinimumSize.Height, _dashboardPage.ClientSize.Height);
            };

            RoundedPanel statusCard = new RoundedPanel();
            statusCard.Dock = DockStyle.Fill;
            statusCard.Padding = new Padding(22);
            statusCard.Margin = new Padding(0, 0, 0, 14);
            TableLayoutPanel statusContent = NewTable(1, 6);
            statusContent.RowStyles.Add(new RowStyle(SizeType.Absolute, 25F));
            statusContent.RowStyles.Add(new RowStyle(SizeType.Absolute, 58F));
            statusContent.RowStyles.Add(new RowStyle(SizeType.Absolute, 26F));
            statusContent.RowStyles.Add(new RowStyle(SizeType.Absolute, 12F));
            statusContent.RowStyles.Add(new RowStyle(SizeType.Percent, 100F));
            statusContent.RowStyles.Add(new RowStyle(SizeType.Absolute, 67F));
            statusCard.Controls.Add(statusContent);
            statusContent.Controls.Add(NewLabel("本地服务 / SESSION CONTROL", 8.5F, FontStyle.Bold, LauncherTheme.Accent), 0, 0);
            _statusLabel = NewLabel("准备启动…", 16F, FontStyle.Bold, LauncherTheme.Ink);
            statusContent.Controls.Add(_statusLabel, 0, 1);
            _stageLabel = NewLabel("环境检查", 9F, FontStyle.Bold, LauncherTheme.PrimaryLight);
            statusContent.Controls.Add(_stageLabel, 0, 2);
            _progressBar = new ActivityBar();
            _progressBar.Dock = DockStyle.Fill;
            _progressBar.Margin = new Padding(0, 2, 0, 3);
            statusContent.Controls.Add(_progressBar, 0, 3);
            _detailLabel = NewLabel("正在检查 Docker、浏览器与端口。首次启动需要下载镜像和模型，请稍候。", 9F, FontStyle.Regular, LauncherTheme.Muted);
            _detailLabel.TextAlign = ContentAlignment.TopLeft;
            _detailLabel.AutoEllipsis = true;
            _detailLabel.Padding = new Padding(0, 10, 0, 2);
            statusContent.Controls.Add(_detailLabel, 0, 4);
            TableLayoutPanel steps = NewTable(4, 1);
            string[] stepNames = new string[] { "环境检查", "构建服务", "健康检查", "进入咨询" };
            _stepLabels = new Label[stepNames.Length];
            for (int index = 0; index < stepNames.Length; index++)
            {
                steps.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 25F));
                _stepLabels[index] = NewLabel("0" + (index + 1) + "\n" + stepNames[index], 9F, FontStyle.Regular, LauncherTheme.Muted);
                _stepLabels[index].Padding = new Padding(0, 10, 0, 0);
                steps.Controls.Add(_stepLabels[index], index, 0);
            }
            statusContent.Controls.Add(steps, 0, 5);
            dashboard.Controls.Add(statusCard, 0, 0);

            TableLayoutPanel notes = NewTable(2, 1);
            notes.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50F));
            notes.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50F));
            RoundedPanel logCard = new RoundedPanel();
            logCard.Dock = DockStyle.Fill;
            logCard.Padding = new Padding(16);
            logCard.Margin = new Padding(0, 0, 7, 0);
            TableLayoutPanel logEntry = NewTable(1, 3);
            logEntry.RowStyles.Add(new RowStyle(SizeType.Absolute, 24F));
            logEntry.RowStyles.Add(new RowStyle(SizeType.Percent, 100F));
            logEntry.RowStyles.Add(new RowStyle(SizeType.Absolute, 35F));
            logEntry.Controls.Add(NewLabel("了解启动进展", 10F, FontStyle.Bold, LauncherTheme.Ink), 0, 0);
            logEntry.Controls.Add(NewLabel("查看实时输出和失败原因。", 8.5F, FontStyle.Regular, LauncherTheme.Muted), 0, 1);
            DashboardButton showLogs = NewButton("查看运行日志  →", LauncherTheme.PrimarySoft);
            showLogs.Click += delegate { ShowPage(true); };
            logEntry.Controls.Add(showLogs, 0, 2);
            logCard.Controls.Add(logEntry);
            notes.Controls.Add(logCard, 0, 0);
            RoundedPanel dataCard = new RoundedPanel();
            dataCard.Dock = DockStyle.Fill;
            dataCard.Padding = new Padding(16);
            dataCard.Margin = new Padding(7, 0, 0, 0);
            TableLayoutPanel dataNote = NewTable(1, 2);
            dataNote.RowStyles.Add(new RowStyle(SizeType.Absolute, 27F));
            dataNote.RowStyles.Add(new RowStyle(SizeType.Percent, 100F));
            dataNote.Controls.Add(NewLabel("安心结束本次运行", 10F, FontStyle.Bold, LauncherTheme.Ink), 0, 0);
            dataNote.Controls.Add(NewLabel("关闭咨询网页或点击结束按钮，\n启动器会停止服务并保留数据卷。\n下次启动可继续查看历史会话。", 8.5F, FontStyle.Regular, LauncherTheme.Muted), 0, 1);
            dataCard.Controls.Add(dataNote);
            notes.Controls.Add(dataCard, 1, 0);
            dashboard.Controls.Add(notes, 0, 1);

            _logsPage = new Panel();
            _logsPage.Dock = DockStyle.Fill;
            _logsPage.Visible = false;
            pageHost.Controls.Add(_logsPage);
            RoundedPanel logsCard = new RoundedPanel();
            logsCard.Dock = DockStyle.Fill;
            logsCard.Padding = new Padding(20);
            _logsPage.Controls.Add(logsCard);
            TableLayoutPanel logsLayout = NewTable(1, 4);
            logsLayout.RowStyles.Add(new RowStyle(SizeType.Absolute, 34F));
            logsLayout.RowStyles.Add(new RowStyle(SizeType.Absolute, 43F));
            logsLayout.RowStyles.Add(new RowStyle(SizeType.Percent, 100F));
            logsLayout.RowStyles.Add(new RowStyle(SizeType.Absolute, 36F));
            logsCard.Controls.Add(logsLayout);
            logsLayout.Controls.Add(NewLabel("运行日志", 17F, FontStyle.Bold, LauncherTheme.Ink), 0, 0);
            TableLayoutPanel logToolbar = NewTable(3, 1);
            logToolbar.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100F));
            logToolbar.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 130F));
            logToolbar.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 124F));
            logToolbar.Controls.Add(NewLabel("实时输出 · 错误输出使用暖红色", 8.5F, FontStyle.Regular, LauncherTheme.Muted), 0, 0);
            DashboardButton openLogFile = NewButton("打开日志文件", LauncherTheme.Subtle);
            openLogFile.Enabled = !string.IsNullOrEmpty(_logFilePath);
            openLogFile.Click += delegate { OpenLogFile(); };
            logToolbar.Controls.Add(openLogFile, 1, 0);
            DashboardButton returnHome = NewButton("返回工作台", LauncherTheme.PrimarySoft);
            returnHome.Click += delegate { ShowPage(false); };
            logToolbar.Controls.Add(returnHome, 2, 0);
            logsLayout.Controls.Add(logToolbar, 0, 1);
            _logBox = new RichTextBox();
            _logBox.Dock = DockStyle.Fill;
            _logBox.Margin = new Padding(0, 10, 0, 0);
            _logBox.ReadOnly = true;
            _logBox.BackColor = LauncherTheme.Canvas;
            _logBox.BorderStyle = BorderStyle.None;
            _logBox.DetectUrls = false;
            _logBox.Font = new Font("Consolas", 9.5F, FontStyle.Regular, GraphicsUnit.Point);
            _logBox.ForeColor = LauncherTheme.Ink;
            logsLayout.Controls.Add(_logBox, 0, 2);
            Label logPath = NewLabel(string.IsNullOrEmpty(_logFilePath) ? "日志文件不可写，实时输出仍可在此查看。" : "日志文件：" + _logFilePath, 8F, FontStyle.Regular, LauncherTheme.Muted);
            logPath.AutoEllipsis = true;
            logsLayout.Controls.Add(logPath, 0, 3);

            TableLayoutPanel footer = NewTable(2, 2);
            footer.Padding = new Padding(0, 14, 0, 0);
            footer.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100F));
            footer.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 162F));
            footer.RowStyles.Add(new RowStyle(SizeType.Percent, 100F));
            footer.RowStyles.Add(new RowStyle(SizeType.Absolute, 25F));
            _latestLogLabel = NewLabel("正在准备本地运行环境…", 8.5F, FontStyle.Regular, LauncherTheme.Muted);
            _latestLogLabel.AutoEllipsis = true;
            _latestLogLabel.Margin = new Padding(0, 0, 14, 0);
            footer.Controls.Add(_latestLogLabel, 0, 0);
            _stopButton = NewButton("结束运行并退出", LauncherTheme.DangerFill);
            _stopButton.ForeColor = LauncherTheme.DangerText;
            _stopButton.Click += delegate { Close(); };
            footer.Controls.Add(_stopButton, 1, 0);
            Label footerNote = NewLabel("本次运行结束时停止服务 · 历史会话与模型缓存保留", 8F, FontStyle.Regular, LauncherTheme.Muted);
            footer.Controls.Add(footerNote, 0, 1);
            footer.SetColumnSpan(footerNote, 2);
            workspace.Controls.Add(footer, 0, 2);
            ShowPage(false);
            ApplyPhase(StartupPhase.Environment);

            Shown += delegate
            {
                if (!previewUi)
                {
                    Task.Run((Action)RunStartupWorkflow);
                }
            };
        }

        private static TableLayoutPanel NewTable(int columns, int rows)
        {
            TableLayoutPanel table = new TableLayoutPanel();
            table.ColumnCount = columns;
            table.RowCount = rows;
            table.Dock = DockStyle.Fill;
            table.BackColor = Color.Transparent;
            table.Margin = new Padding(0);
            return table;
        }

        private Label NewLabel(string text, float size, FontStyle style, Color color)
        {
            Label label = new Label();
            label.Text = text;
            label.Font = new Font(Font.FontFamily, size, style);
            label.ForeColor = color;
            label.BackColor = Color.Transparent;
            label.Dock = DockStyle.Fill;
            label.Margin = new Padding(0);
            label.TextAlign = ContentAlignment.MiddleLeft;
            return label;
        }

        private DashboardButton NewButton(string text, Color fill)
        {
            DashboardButton button = new DashboardButton();
            button.Text = text;
            button.Font = new Font(Font.FontFamily, 9F, FontStyle.Bold);
            button.Dock = DockStyle.Fill;
            button.FillColor = fill;
            button.Margin = new Padding(0, 3, 0, 3);
            return button;
        }

        private void ShowPage(bool showLogs)
        {
            _dashboardPage.Visible = !showLogs;
            _logsPage.Visible = showLogs;
            if (showLogs)
            {
                _logsPage.BringToFront();
            }
            else
            {
                _dashboardPage.BringToFront();
            }
            _dashboardButton.FillColor = showLogs ? LauncherTheme.Surface : LauncherTheme.PrimarySoft;
            _dashboardButton.ForeColor = showLogs ? LauncherTheme.Muted : LauncherTheme.PrimaryLight;
            _logsButton.FillColor = showLogs ? LauncherTheme.PrimarySoft : LauncherTheme.Surface;
            _logsButton.ForeColor = showLogs ? LauncherTheme.PrimaryLight : LauncherTheme.Muted;
            _dashboardButton.Invalidate();
            _logsButton.Invalidate();
        }

        private void OpenLogFile()
        {
            try
            {
                if (!string.IsNullOrEmpty(_logFilePath))
                {
                    ProcessStartInfo info = new ProcessStartInfo(_logFilePath);
                    info.UseShellExecute = true;
                    Process.Start(info);
                }
            }
            catch (Exception exception)
            {
                AppendLog("无法打开日志文件：" + Sanitize(exception.Message), true);
            }
        }

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            // Unsupported DWM attributes are ignored on older Windows versions.
            try
            {
                int enabled = 1;
                if (DwmSetWindowAttribute(Handle, 20, ref enabled, sizeof(int)) != 0)
                {
                    DwmSetWindowAttribute(Handle, 19, ref enabled, sizeof(int));
                }
                int rounded = 2;
                DwmSetWindowAttribute(Handle, 33, ref rounded, sizeof(int));
            }
            catch (DllNotFoundException) { }
            catch (EntryPointNotFoundException) { }
        }

        [DllImport("dwmapi.dll")]
        private static extern int DwmSetWindowAttribute(IntPtr window, int attribute, ref int value, int valueSize);

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            if (_allowClose)
            {
                base.OnFormClosing(e);
                return;
            }

            e.Cancel = true;
            BeginShutdown(null);
            base.OnFormClosing(e);
        }

        private void RunStartupWorkflow()
        {
            try
            {
                SetStatus("正在检查运行环境…", StartupPhase.Environment);
                AppendLog("检查项目目录、Docker 和浏览器。", false);
                _paths = LauncherPaths.Discover();

                _keyMaterial = ApiKeyResolver.Resolve(_paths.BackendDirectory);
                if (_keyMaterial == null)
                {
                    throw new InvalidOperationException(
                        "未找到有效的 DEEPSEEK_API_KEY。请在 Windows 用户环境变量中设置，或写入 dk-ai-agent\\.env 后重试。"
                    );
                }

                AppendLog("已读取 DEEPSEEK_API_KEY（来源：" + _keyMaterial.Source + "，内容不会显示）。", false);
                if (!RunPortPreflight())
                {
                    AppendLog("用户取消了启动。", false);
                    BeginShutdown(null);
                    return;
                }
                EnsureDockerReady();
                _cancellation.Token.ThrowIfCancellationRequested();
                CleanupStaleComposeProject();
                _cancellation.Token.ThrowIfCancellationRequested();

                SetStatus("正在构建并启动服务…", StartupPhase.Build);
                AppendLog("执行 Docker Compose 构建与启动，网页端口为 " + _frontendPort + "。首次启动还会下载镜像和模型。", false);
                _composeWasAttempted = true;
                CommandResult upResult = RunCommand(
                    _paths.DockerCli,
                    ComposeArguments("up -d --build"),
                    _paths.BackendDirectory,
                    true,
                    Timeout.Infinite,
                    false
                );

                if (upResult.ExitCode != 0)
                {
                    throw new InvalidOperationException(ClassifyComposeFailure(upResult));
                }

                WaitForApplicationHealth();
                _cancellation.Token.ThrowIfCancellationRequested();

                SetStatus("服务已就绪，正在打开咨询网页…", StartupPhase.Opening);
                AppendLog("健康检查通过，打开独立浏览器窗口。", false);
                OpenBrowserAndWait();

                if (!_cancellation.IsCancellationRequested)
                {
                    SetStatus("咨询网页已关闭，正在停止后台服务…", StartupPhase.Stopping);
                    AppendLog("检测到咨询网页关闭。", false);
                    BeginShutdown(null);
                }
            }
            catch (OperationCanceledException)
            {
                BeginShutdown(null);
            }
            catch (Exception exception)
            {
                string safeMessage = Sanitize(exception.Message);
                AppendLog("启动失败：" + safeMessage, true);
                SetStatus("启动失败，正在清理后台服务…", StartupPhase.Stopping);
                BeginShutdown(safeMessage);
            }
        }

        private void EnsureDockerReady()
        {
            SetStatus("正在连接 Docker Engine…", StartupPhase.Environment);
            if (IsDockerReady())
            {
                AppendLog("Docker Engine 已就绪。", false);
                return;
            }

            if (string.IsNullOrEmpty(_paths.DockerDesktop) || !File.Exists(_paths.DockerDesktop))
            {
                throw new InvalidOperationException("Docker Engine 未运行，并且没有找到 Docker Desktop。请先安装 Docker Desktop。");
            }

            AppendLog("Docker Engine 尚未运行，正在启动 Docker Desktop。", false);
            ProcessStartInfo startInfo = new ProcessStartInfo();
            startInfo.FileName = _paths.DockerDesktop;
            startInfo.UseShellExecute = true;
            startInfo.WorkingDirectory = Path.GetDirectoryName(_paths.DockerDesktop);
            Process.Start(startInfo);

            DateTime deadline = DateTime.UtcNow.AddMinutes(3);
            int attempt = 0;
            while (DateTime.UtcNow < deadline)
            {
                _cancellation.Token.ThrowIfCancellationRequested();
                attempt++;
                if (IsDockerReady())
                {
                    AppendLog("Docker Engine 已就绪。", false);
                    return;
                }

                if (attempt % 4 == 0)
                {
                    AppendLog("仍在等待 Docker Engine 启动...", false);
                }

                SleepWithCancellation(3000);
            }

            throw new TimeoutException(
                "等待 Docker Engine 超时。请打开 Docker Desktop，确认状态为 Running 后重新启动本程序。"
            );
        }

        private bool IsDockerReady()
        {
            try
            {
                CommandResult result = RunCommand(
                    _paths.DockerCli,
                    "info",
                    _paths.BackendDirectory,
                    false,
                    10000,
                    false
                );
                return result.ExitCode == 0 && !result.TimedOut;
            }
            catch (OperationCanceledException)
            {
                throw;
            }
            catch
            {
                return false;
            }
        }

        private string InitializeLogFile()
        {
            string header = "AI 心理咨询师启动日志 " + DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + Environment.NewLine;
            try
            {
                string directory = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                    "DkPsychCounselorLauncher"
                );
                Directory.CreateDirectory(directory);
                string path = Path.Combine(directory, "launcher-last.log");
                if (TryInitializeLogFile(path, header))
                {
                    return path;
                }
            }
            catch
            {
                // Fall back to the executable directory below.
            }

            string fallbackPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "launcher-last.log");
            return TryInitializeLogFile(fallbackPath, header) ? fallbackPath : null;
        }

        private static bool TryInitializeLogFile(string path, string header)
        {
            try
            {
                File.WriteAllText(path, header, new UTF8Encoding(false));
                return true;
            }
            catch
            {
                return false;
            }
        }

        private void CleanupStaleComposeProject()
        {
            SetStatus("正在清理上次异常退出留下的服务…", StartupPhase.Environment);
            AppendLog("检查并清理同名 Compose 项目的残留容器；不会删除数据卷。", false);
            CommandResult result = RunCommand(
                _paths.DockerCli,
                ComposeArguments("down --remove-orphans"),
                _paths.BackendDirectory,
                true,
                120000,
                false
            );

            if (result.ExitCode != 0)
            {
                AppendLog("残留容器清理未完全成功，将继续执行端口检查。", true);
            }
        }

        private bool RunPortPreflight()
        {
            SetStatus("正在检查启动端口…", StartupPhase.Environment);
            _frontendPort = PortInspector.ResolvePreferredPort(_paths.BackendDirectory);

            List<PortUsage> usages = new List<PortUsage>();
            AddPortUsage(usages, _frontendPort, "网页端口（必须）", true);
            AddPortUsage(usages, 5432, "PostgreSQL 本地调试端口", false);
            AddPortUsage(usages, 8001, "Python Worker 本地调试端口", false);
            AddPortUsage(usages, 8123, "Java 后端本地调试端口", false);

            PortUsage requiredUsage = null;
            bool hasOccupiedDevelopmentPort = false;
            foreach (PortUsage usage in usages)
            {
                usage.DockerOwners.AddRange(GetDockerContainersPublishingPort(usage.Port));
                if (usage.IsRequired)
                {
                    requiredUsage = usage;
                }
                else if (usage.IsOccupied)
                {
                    hasOccupiedDevelopmentPort = true;
                }
            }

            string report = BuildPortReport(usages);
            AppendLog(report.Replace(Environment.NewLine, " | "), false);

            if (requiredUsage == null || !requiredUsage.IsOccupied)
            {
                if (hasOccupiedDevelopmentPort)
                {
                    ShowMessageBoxOnUiThread(
                        report + Environment.NewLine + Environment.NewLine +
                        "上述调试端口不会被一键启动模式映射到宿主机，因此不影响本次启动，也不会自动清理。",
                        "端口检查",
                        MessageBoxButtons.OK,
                        MessageBoxIcon.Information
                    );
                }

                AppendLog("网页端口 " + _frontendPort + " 可用。", false);
                return true;
            }

            DialogResult choice = ShowMessageBoxOnUiThread(
                report + Environment.NewLine + Environment.NewLine +
                "网页端口 " + _frontendPort + " 被占用，无法直接启动。" + Environment.NewLine +
                "选择“是”：清理上面列出的网页端口占用者后继续。" + Environment.NewLine +
                "选择“否”：保留占用者，自动改用 3002-3010 中的空闲端口。" + Environment.NewLine +
                "选择“取消”：终止本次启动。" + Environment.NewLine + Environment.NewLine +
                "启动器不会结束 Docker Desktop、WSL 转发或系统关键进程。",
                "网页端口被占用",
                MessageBoxButtons.YesNoCancel,
                MessageBoxIcon.Warning
            );

            if (choice == DialogResult.Cancel)
            {
                return false;
            }

            if (choice == DialogResult.Yes)
            {
                if (TryClearPort(requiredUsage))
                {
                    AppendLog("网页端口 " + _frontendPort + " 已清理并可用。", false);
                    return true;
                }

                DialogResult fallbackChoice = ShowMessageBoxOnUiThread(
                    "无法安全清理网页端口 " + _frontendPort + "。是否改用 3002-3010 中的空闲端口？",
                    "端口未清理",
                    MessageBoxButtons.YesNo,
                    MessageBoxIcon.Warning
                );
                if (fallbackChoice != DialogResult.Yes)
                {
                    return false;
                }
            }

            int fallbackPort = PortInspector.FindAlternativePort();
            if (fallbackPort == 0)
            {
                throw new InvalidOperationException("网页端口被占用，并且 3002-3010 也没有可用端口。请关闭占用程序后重试。");
            }

            _frontendPort = fallbackPort;
            AppendLog("保留原端口占用者，本次改用网页端口 " + _frontendPort + "。", false);
            return true;
        }

        private void AddPortUsage(List<PortUsage> usages, int port, string description, bool isRequired)
        {
            foreach (PortUsage existing in usages)
            {
                if (existing.Port == port)
                {
                    if (isRequired)
                    {
                        existing.IsRequired = true;
                        existing.Description = description;
                    }
                    return;
                }
            }

            PortUsage usage = PortInspector.Inspect(port);
            usage.Description = description;
            usage.IsRequired = isRequired;
            usages.Add(usage);
        }

        private string BuildPortReport(IEnumerable<PortUsage> usages)
        {
            StringBuilder builder = new StringBuilder();
            builder.AppendLine("启动端口检查结果：");
            foreach (PortUsage usage in usages)
            {
                builder.Append("- ").Append(usage.Port).Append("（").Append(usage.Description).Append("）：");
                if (!usage.IsOccupied)
                {
                    builder.AppendLine("空闲");
                    continue;
                }

                builder.AppendLine("已占用");
                foreach (PortOwner owner in usage.ProcessOwners)
                {
                    builder.Append("    PID ").Append(owner.ProcessId).Append(" / ").AppendLine(owner.ProcessName);
                }
                foreach (DockerPortOwner owner in usage.DockerOwners)
                {
                    builder.Append("    Docker 容器 ").Append(owner.Name).Append(" / ").AppendLine(owner.Id);
                }
                if (usage.ProcessOwners.Count == 0 && usage.DockerOwners.Count == 0)
                {
                    builder.AppendLine("    未能识别占用者（可能是系统代理或权限受限进程）");
                }
            }

            return builder.ToString().TrimEnd();
        }

        private List<DockerPortOwner> GetDockerContainersPublishingPort(int port)
        {
            List<DockerPortOwner> owners = new List<DockerPortOwner>();
            try
            {
                CommandResult result = RunCommand(
                    _paths.DockerCli,
                    "ps --filter " + Quote("publish=" + port) + " --format " + Quote("{{.ID}}|{{.Names}}"),
                    _paths.BackendDirectory,
                    false,
                    15000,
                    false
                );
                if (result.ExitCode != 0 || string.IsNullOrWhiteSpace(result.Output))
                {
                    return owners;
                }

                foreach (string rawLine in result.Output.Split(new string[] { "\r\n", "\n" }, StringSplitOptions.RemoveEmptyEntries))
                {
                    string[] parts = rawLine.Trim().Split(new char[] { '|' }, 2);
                    if (parts.Length != 2 || !IsSafeContainerId(parts[0]))
                    {
                        continue;
                    }

                    owners.Add(new DockerPortOwner(parts[0], parts[1]));
                }
            }
            catch (OperationCanceledException)
            {
                throw;
            }
            catch (Exception exception)
            {
                AppendLog("无法读取端口 " + port + " 的 Docker 占用信息：" + Sanitize(exception.Message), true);
            }

            return owners;
        }

        private bool TryClearPort(PortUsage usage)
        {
            if (usage == null)
            {
                return false;
            }

            foreach (DockerPortOwner owner in usage.DockerOwners)
            {
                AppendLog("按用户确认停止占用端口 " + usage.Port + " 的 Docker 容器：" + owner.Name + "。", false);
                CommandResult stopResult = RunCommand(
                    _paths.DockerCli,
                    "stop " + owner.Id,
                    _paths.BackendDirectory,
                    true,
                    60000,
                    false
                );
                if (stopResult.ExitCode != 0)
                {
                    AppendLog("Docker 容器 " + owner.Name + " 未能正常停止。", true);
                }
            }

            WaitForPortRelease(usage.Port, 3000);
            PortUsage refreshed = PortInspector.Inspect(usage.Port);
            if (!refreshed.IsOccupied)
            {
                return true;
            }

            foreach (PortOwner owner in refreshed.ProcessOwners)
            {
                if (!WasConfirmedPortOwner(usage, owner))
                {
                    AppendLog("端口 " + usage.Port + " 的占用者已发生变化，不会结束新的进程：PID " + owner.ProcessId + " / " + owner.ProcessName + "。", true);
                    continue;
                }

                if (IsProtectedPortOwner(owner))
                {
                    AppendLog("出于安全原因不结束进程：PID " + owner.ProcessId + " / " + owner.ProcessName + "。", true);
                    continue;
                }

                try
                {
                    Process process = Process.GetProcessById(owner.ProcessId);
                    AppendLog("按用户确认结束占用端口 " + usage.Port + " 的进程：PID " + owner.ProcessId + " / " + owner.ProcessName + "。", false);
                    process.Kill();
                    process.WaitForExit(5000);
                    process.Dispose();
                }
                catch (Exception exception)
                {
                    AppendLog("无法结束 PID " + owner.ProcessId + "：" + Sanitize(exception.Message), true);
                }
            }

            return WaitForPortRelease(usage.Port, 10000);
        }

        private static bool WasConfirmedPortOwner(PortUsage originalUsage, PortOwner currentOwner)
        {
            foreach (PortOwner confirmedOwner in originalUsage.ProcessOwners)
            {
                bool sameStartTime = confirmedOwner.StartTimeUtcTicks == 0 ||
                    currentOwner.StartTimeUtcTicks == 0 ||
                    confirmedOwner.StartTimeUtcTicks == currentOwner.StartTimeUtcTicks;
                if (confirmedOwner.ProcessId == currentOwner.ProcessId &&
                    sameStartTime &&
                    string.Equals(confirmedOwner.ProcessName, currentOwner.ProcessName, StringComparison.OrdinalIgnoreCase))
                {
                    return true;
                }
            }

            return false;
        }

        private bool WaitForPortRelease(int port, int timeoutMilliseconds)
        {
            Stopwatch stopwatch = Stopwatch.StartNew();
            do
            {
                _cancellation.Token.ThrowIfCancellationRequested();
                if (PortInspector.IsAvailable(port))
                {
                    return true;
                }
                Thread.Sleep(250);
            }
            while (stopwatch.ElapsedMilliseconds < timeoutMilliseconds);

            return PortInspector.IsAvailable(port);
        }

        private static bool IsProtectedPortOwner(PortOwner owner)
        {
            if (owner == null || owner.ProcessId <= 4 || owner.ProcessId == Process.GetCurrentProcess().Id)
            {
                return true;
            }

            string name = (owner.ProcessName ?? string.Empty).Trim();
            string[] protectedNames = new string[]
            {
                "System", "Idle", "Docker Desktop", "com.docker.backend", "dockerd", "wslrelay", "vpnkit"
            };
            foreach (string protectedName in protectedNames)
            {
                if (string.Equals(name, protectedName, StringComparison.OrdinalIgnoreCase))
                {
                    return true;
                }
            }

            return false;
        }

        private DialogResult ShowMessageBoxOnUiThread(
            string text,
            string caption,
            MessageBoxButtons buttons,
            MessageBoxIcon icon)
        {
            if (InvokeRequired)
            {
                return (DialogResult)Invoke(new Func<DialogResult>(delegate
                {
                    return MessageBox.Show(this, text, caption, buttons, icon);
                }));
            }

            return MessageBox.Show(this, text, caption, buttons, icon);
        }

        private static bool IsSafeContainerId(string value)
        {
            if (string.IsNullOrEmpty(value) || value.Length > 64)
            {
                return false;
            }

            foreach (char character in value)
            {
                bool isHex = (character >= '0' && character <= '9') ||
                    (character >= 'a' && character <= 'f') ||
                    (character >= 'A' && character <= 'F');
                if (!isHex)
                {
                    return false;
                }
            }

            return true;
        }

        private string ClassifyComposeFailure(CommandResult result)
        {
            string output = result == null ? string.Empty : (result.Output ?? string.Empty);
            string normalized = output.ToLowerInvariant();

            if (normalized.Contains("port is already allocated") ||
                normalized.Contains("address already in use") ||
                normalized.Contains("ports are not available") ||
                normalized.Contains("failed to bind") ||
                normalized.Contains("bind:"))
            {
                return "Docker Compose 无法绑定网页端口 " + _frontendPort + "。该端口在检查后被其他程序抢占，请重新启动。";
            }

            if (normalized.Contains("tls handshake timeout") ||
                normalized.Contains("failed to fetch anonymous token") ||
                normalized.Contains("connection reset") ||
                normalized.Contains("forcibly closed") ||
                normalized.Contains("i/o timeout") ||
                normalized.Contains("docker.io"))
            {
                return "Docker 镜像下载失败，本次不是端口占用。请检查 Docker Desktop 的网络或 Docker Hub 连接后重试。";
            }

            if (normalized.Contains("no space left on device"))
            {
                return "Docker 磁盘空间不足。请在 Docker Desktop 中清理无用镜像或扩容后重试。";
            }

            return "Docker Compose 启动失败。请查看启动器日志中的原始 Docker 输出。";
        }

        private void WaitForApplicationHealth()
        {
            SetStatus("服务正在初始化，等待健康检查…", StartupPhase.Health);
            AppendLog("等待 " + HealthUrl + "。首次灌入向量库时会明显更久。", false);

            DateTime deadline = DateTime.UtcNow.AddMinutes(20);
            DateTime nextProgressLog = DateTime.UtcNow.AddSeconds(15);
            while (DateTime.UtcNow < deadline)
            {
                _cancellation.Token.ThrowIfCancellationRequested();
                if (IsHealthEndpointReady())
                {
                    return;
                }

                if (DateTime.UtcNow >= nextProgressLog)
                {
                    AppendLog("后端仍在初始化，请继续等待...", false);
                    nextProgressLog = DateTime.UtcNow.AddSeconds(15);
                }

                SleepWithCancellation(2000);
            }

            AppendLog("健康检查超时，输出当前容器状态：", true);
            RunCommand(
                _paths.DockerCli,
                ComposeArguments("ps"),
                _paths.BackendDirectory,
                true,
                30000,
                false
            );
            throw new TimeoutException("服务在 20 分钟内未通过健康检查，请查看容器状态和启动日志。");
        }

        private bool IsHealthEndpointReady()
        {
            try
            {
                HttpWebRequest request = (HttpWebRequest)WebRequest.Create(HealthUrl);
                request.Method = "GET";
                request.KeepAlive = false;
                request.Proxy = null;
                request.Timeout = 3000;
                request.ReadWriteTimeout = 3000;
                using (HttpWebResponse response = (HttpWebResponse)request.GetResponse())
                {
                    int status = (int)response.StatusCode;
                    return status >= 200 && status < 300;
                }
            }
            catch
            {
                return false;
            }
        }

        private void OpenBrowserAndWait()
        {
            _browserProfileDirectory = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "DkPsychCounselorLauncher",
                "browser-" + Process.GetCurrentProcess().Id
            );
            Directory.CreateDirectory(_browserProfileDirectory);

            ProcessStartInfo startInfo = new ProcessStartInfo();
            startInfo.FileName = _paths.BrowserExecutable;
            startInfo.WorkingDirectory = Path.GetDirectoryName(_paths.BrowserExecutable);
            startInfo.UseShellExecute = true;
            startInfo.Arguments =
                "--app=" + Quote(AppUrl) +
                " --user-data-dir=" + Quote(_browserProfileDirectory) +
                " --no-first-run --disable-background-mode";

            Process browser = Process.Start(startInfo);
            if (browser == null)
            {
                throw new InvalidOperationException("浏览器启动失败。");
            }

            lock (_processLock)
            {
                _browserProcess = browser;
            }

            SetStatus("咨询网页已打开，可以开始了。", StartupPhase.Ready);
            AppendLog("咨询网页：" + AppUrl, false);

            try
            {
                while (!browser.WaitForExit(250))
                {
                    _cancellation.Token.ThrowIfCancellationRequested();
                }
            }
            finally
            {
                lock (_processLock)
                {
                    if (ReferenceEquals(_browserProcess, browser))
                    {
                        _browserProcess = null;
                    }
                }

                browser.Dispose();
            }
        }

        private void BeginShutdown(string startupError)
        {
            if (Interlocked.Exchange(ref _shutdownStarted, 1) != 0)
            {
                return;
            }

            _cancellation.Cancel();
            SetStatus("正在停止后台服务，请稍候…", StartupPhase.Stopping);
            SetStopButtonEnabled(false);
            StopRunningProcesses();

            Task.Run(delegate
            {
                bool cleanupSucceeded = true;
                try
                {
                    if (_composeWasAttempted && _paths != null && !string.IsNullOrEmpty(_paths.DockerCli))
                    {
                        AppendLog("执行 Docker Compose 停止；数据卷不会删除。", false);
                        CommandResult result = RunCommand(
                            _paths.DockerCli,
                            ComposeArguments("down --remove-orphans"),
                            _paths.BackendDirectory,
                            true,
                            120000,
                            true
                        );
                        if (result.ExitCode != 0)
                        {
                            cleanupSucceeded = false;
                            AppendLog("后台服务停止命令未正常完成，请稍后运行 docker compose down 检查。", true);
                        }
                        else
                        {
                            AppendLog("本项目后台服务已停止；数据卷已保留。", false);
                        }
                    }
                }
                catch (Exception exception)
                {
                    cleanupSucceeded = false;
                    AppendLog("停止服务时发生错误：" + Sanitize(exception.Message), true);
                }
                finally
                {
                    DeleteBrowserProfileBestEffort();
                    CompleteShutdown(startupError, cleanupSucceeded);
                }
            });
        }

        private void StopRunningProcesses()
        {
            Process browser;
            Process command;
            lock (_processLock)
            {
                browser = _browserProcess;
                command = _commandProcess;
            }

            SafeKill(browser);
            SafeKill(command);
        }

        private static void SafeKill(Process process)
        {
            if (process == null)
            {
                return;
            }

            try
            {
                if (!process.HasExited)
                {
                    process.Kill();
                    process.WaitForExit(5000);
                }
            }
            catch
            {
                // The process may already have exited between the checks.
            }
        }

        private void CompleteShutdown(string startupError, bool cleanupSucceeded)
        {
            RunOnUiThread(delegate
            {
                _allowClose = true;
                if (!string.IsNullOrEmpty(startupError) || !cleanupSucceeded)
                {
                    ApplyPhase(StartupPhase.Failed);
                    _statusLabel.Text = !string.IsNullOrEmpty(startupError) ? "启动失败，请查看原因后重试。" : "停止服务未确认，请检查运行日志。";
                    _detailLabel.Text = (!string.IsNullOrEmpty(startupError) ? startupError + Environment.NewLine : string.Empty) +
                        (cleanupSucceeded ? "清理流程已结束。修复原因后，关闭本窗口并重新启动。" : "后台服务可能仍在运行。请查看日志并确认 Docker Compose 停止结果。") ;
                    _stopButton.Text = "退出启动器";
                    _stopButton.Enabled = true;
                    _latestLogLabel.Text = "本次运行已结束；可查看运行日志排查原因。";
                    ShowPage(false);
                    return;
                }
                ApplyPhase(StartupPhase.Stopped);
                _statusLabel.Text = "后台服务已停止。";
                Close();
            });
        }

        private void DeleteBrowserProfileBestEffort()
        {
            if (string.IsNullOrEmpty(_browserProfileDirectory) || !Directory.Exists(_browserProfileDirectory))
            {
                return;
            }

            for (int attempt = 0; attempt < 3; attempt++)
            {
                try
                {
                    Directory.Delete(_browserProfileDirectory, true);
                    return;
                }
                catch
                {
                    Thread.Sleep(500);
                }
            }
        }

        private CommandResult RunCommand(
            string executable,
            string arguments,
            string workingDirectory,
            bool logOutput,
            int timeoutMilliseconds,
            bool ignoreCancellation)
        {
            ProcessStartInfo startInfo = new ProcessStartInfo();
            startInfo.FileName = executable;
            startInfo.Arguments = arguments;
            startInfo.WorkingDirectory = workingDirectory;
            startInfo.UseShellExecute = false;
            startInfo.CreateNoWindow = true;
            startInfo.RedirectStandardOutput = true;
            startInfo.RedirectStandardError = true;
            startInfo.EnvironmentVariables["COMPOSE_ANSI"] = "never";
            startInfo.EnvironmentVariables["BUILDKIT_PROGRESS"] = "plain";
            if (_frontendPort > 0)
            {
                startInfo.EnvironmentVariables["FRONTEND_PORT"] = _frontendPort.ToString();
            }
            if (_keyMaterial != null && !string.IsNullOrEmpty(_keyMaterial.Value))
            {
                startInfo.EnvironmentVariables["DEEPSEEK_API_KEY"] = _keyMaterial.Value;
            }

            Process process = new Process();
            process.StartInfo = startInfo;
            process.EnableRaisingEvents = true;
            StringBuilder capturedOutput = new StringBuilder();
            object capturedOutputLock = new object();
            process.OutputDataReceived += delegate(object sender, DataReceivedEventArgs eventArgs)
            {
                if (!string.IsNullOrWhiteSpace(eventArgs.Data))
                {
                    CaptureOutputLine(capturedOutput, capturedOutputLock, eventArgs.Data);
                    if (logOutput)
                    {
                        AppendLog(eventArgs.Data, false);
                    }
                }
            };
            process.ErrorDataReceived += delegate(object sender, DataReceivedEventArgs eventArgs)
            {
                if (!string.IsNullOrWhiteSpace(eventArgs.Data))
                {
                    CaptureOutputLine(capturedOutput, capturedOutputLock, eventArgs.Data);
                    if (logOutput)
                    {
                        AppendLog(eventArgs.Data, true);
                    }
                }
            };

            lock (_processLock)
            {
                _commandProcess = process;
            }

            bool timedOut = false;
            try
            {
                if (!process.Start())
                {
                    throw new InvalidOperationException("无法启动命令：" + Path.GetFileName(executable));
                }

                process.BeginOutputReadLine();
                process.BeginErrorReadLine();

                Stopwatch stopwatch = Stopwatch.StartNew();
                while (!process.WaitForExit(200))
                {
                    if (!ignoreCancellation && _cancellation.IsCancellationRequested)
                    {
                        SafeKill(process);
                        throw new OperationCanceledException();
                    }

                    if (timeoutMilliseconds != Timeout.Infinite && stopwatch.ElapsedMilliseconds >= timeoutMilliseconds)
                    {
                        timedOut = true;
                        SafeKill(process);
                        break;
                    }
                }

                process.WaitForExit();
                int exitCode = timedOut ? -1 : process.ExitCode;
                string output;
                lock (capturedOutputLock)
                {
                    output = capturedOutput.ToString();
                }
                return new CommandResult(exitCode, timedOut, output);
            }
            finally
            {
                lock (_processLock)
                {
                    if (ReferenceEquals(_commandProcess, process))
                    {
                        _commandProcess = null;
                    }
                }

                process.Dispose();
            }
        }

        private static void CaptureOutputLine(StringBuilder output, object outputLock, string line)
        {
            const int MaximumCapturedCharacters = 131072;
            lock (outputLock)
            {
                output.AppendLine(line);
                if (output.Length > MaximumCapturedCharacters)
                {
                    output.Remove(0, output.Length - MaximumCapturedCharacters);
                }
            }
        }

        private string ComposeArguments(string command)
        {
            return "compose -p " + ComposeProjectName + " -f " + Quote(_paths.ComposeFile) + " " + command;
        }

        private void SleepWithCancellation(int milliseconds)
        {
            if (_cancellation.Token.WaitHandle.WaitOne(milliseconds))
            {
                throw new OperationCanceledException();
            }
        }

        private static string Quote(string value)
        {
            return "\"" + value.Replace("\"", "\\\"") + "\"";
        }

        private string Sanitize(string value)
        {
            if (string.IsNullOrEmpty(value))
            {
                return string.Empty;
            }

            if (_keyMaterial != null && !string.IsNullOrEmpty(_keyMaterial.Value))
            {
                value = value.Replace(_keyMaterial.Value, "[REDACTED]");
            }

            return value;
        }

        private void AppendLog(string message, bool isError)
        {
            string safeMessage = Sanitize(message);
            string line = "[" + DateTime.Now.ToString("HH:mm:ss") + "] " + safeMessage + Environment.NewLine;
            if (!string.IsNullOrEmpty(_logFilePath))
            {
                try
                {
                    lock (_logFileLock)
                    {
                        File.AppendAllText(_logFilePath, line, new UTF8Encoding(false));
                    }
                }
                catch
                {
                    // The on-screen log remains available if the log file cannot be written.
                }
            }

            RunOnUiThread(delegate
            {
                if (_logBox.TextLength > 60000)
                {
                    _logBox.Select(0, 20000);
                    _logBox.SelectedText = string.Empty;
                }

                _logBox.SelectionStart = _logBox.TextLength;
                _logBox.SelectionColor = isError ? LauncherTheme.DangerText : LauncherTheme.Ink;
                _logBox.AppendText(line);
                _logBox.SelectionStart = _logBox.TextLength;
                _logBox.ScrollToCaret();
                _latestLogLabel.Text = safeMessage.Replace(Environment.NewLine, " ");
                _latestLogLabel.ForeColor = isError ? LauncherTheme.DangerText : LauncherTheme.Muted;
            });
        }

        private void SetStatus(string status, StartupPhase phase)
        {
            RunOnUiThread(delegate
            {
                _statusLabel.Text = status;
                ApplyPhase(phase);
            });
        }

        private void ApplyPhase(StartupPhase phase)
        {
            Color accent = LauncherTheme.Primary;
            bool busy = true;
            string[] names = new string[] { "环境检查", "构建服务", "健康检查", "进入咨询" };
            string detail;
            if (phase == StartupPhase.Environment)
            {
                _activeStep = 0;
                _stateBadge.Text = "正在启动";
                _stageLabel.Text = "环境检查";
                detail = "自动检查 Docker、浏览器与端口，然后准备本地服务。";
            }
            else if (phase == StartupPhase.Build)
            {
                _activeStep = 1;
                _stateBadge.Text = "正在构建";
                _stageLabel.Text = "构建与启动服务";
                detail = "首次启动需要下载镜像和模型，耗时取决于网络与设备。可以打开运行日志查看进展。";
            }
            else if (phase == StartupPhase.Health)
            {
                _activeStep = 2;
                _stateBadge.Text = "正在初始化";
                _stageLabel.Text = "等待应用健康检查";
                detail = "服务正在初始化并准备知识库。健康检查通过后，会自动打开独立咨询网页。";
            }
            else if (phase == StartupPhase.Opening || phase == StartupPhase.Ready)
            {
                _activeStep = phase == StartupPhase.Ready ? 4 : 3;
                _stateBadge.Text = phase == StartupPhase.Ready ? "咨询已打开" : "服务已就绪";
                _stageLabel.Text = phase == StartupPhase.Ready ? "本次咨询正在运行" : "打开咨询网页";
                accent = LauncherTheme.Accent;
                busy = phase != StartupPhase.Ready;
                detail = "咨询地址：" + AppUrl + Environment.NewLine + "关闭咨询网页或结束本次运行时，将停止后台服务并保留数据卷。";
            }
            else if (phase == StartupPhase.Stopping)
            {
                _stateBadge.Text = "正在结束";
                _stageLabel.Text = "停止服务与清理本次运行";
                detail = "正在结束浏览器与 Docker Compose 服务。请等待清理完成；历史会话和模型缓存会保留。";
            }
            else
            {
                busy = false;
                accent = phase == StartupPhase.Failed ? LauncherTheme.DangerText : LauncherTheme.Accent;
                _stateBadge.Text = phase == StartupPhase.Failed ? "需要处理" : "运行已结束";
                _stageLabel.Text = phase == StartupPhase.Failed ? "查看运行日志，确认失败原因" : "服务已停止";
                detail = phase == StartupPhase.Failed ? "修复失败原因后，请关闭本窗口并重新打开启动器。" : "历史会话与模型缓存已保留。";
            }

            _detailLabel.Text = detail;
            _stateBadge.ForeColor = accent;
            _statusLabel.ForeColor = phase == StartupPhase.Failed ? LauncherTheme.DangerText : LauncherTheme.Ink;
            _stageLabel.ForeColor = phase == StartupPhase.Failed ? LauncherTheme.DangerText : LauncherTheme.PrimaryLight;
            _progressBar.SetActivity(busy, accent);
            for (int index = 0; index < _stepLabels.Length; index++)
            {
                bool complete = index < _activeStep;
                bool current = index == _activeStep;
                _stepLabels[index].Text = (complete ? "✓" : "0" + (index + 1)) + Environment.NewLine + names[index];
                _stepLabels[index].ForeColor = complete ? LauncherTheme.Accent :
                    (current ? (phase == StartupPhase.Failed ? LauncherTheme.DangerText : LauncherTheme.PrimaryLight) : LauncherTheme.Muted);
            }
        }

        private void SetStopButtonEnabled(bool enabled)
        {
            RunOnUiThread(delegate { _stopButton.Enabled = enabled; });
        }

        private void RunOnUiThread(Action action)
        {
            if (IsDisposed || Disposing)
            {
                return;
            }

            try
            {
                if (InvokeRequired)
                {
                    BeginInvoke(action);
                }
                else
                {
                    action();
                }
            }
            catch (InvalidOperationException)
            {
                // The window may have been disposed during shutdown.
            }
        }
    }

    internal enum StartupPhase
    {
        Environment,
        Build,
        Health,
        Opening,
        Ready,
        Stopping,
        Failed,
        Stopped
    }

    internal static class LauncherTheme
    {
        internal static readonly Color Canvas = Color.FromArgb(11, 14, 21);
        internal static readonly Color Surface = Color.FromArgb(22, 27, 38);
        internal static readonly Color Subtle = Color.FromArgb(29, 37, 50);
        internal static readonly Color Line = Color.FromArgb(42, 50, 66);
        internal static readonly Color Primary = Color.FromArgb(99, 102, 241);
        internal static readonly Color PrimarySoft = Color.FromArgb(35, 42, 82);
        internal static readonly Color PrimaryLight = Color.FromArgb(165, 180, 252);
        internal static readonly Color Accent = Color.FromArgb(34, 211, 238);
        internal static readonly Color Ink = Color.FromArgb(229, 231, 235);
        internal static readonly Color Muted = Color.FromArgb(156, 163, 175);
        internal static readonly Color DangerFill = Color.FromArgb(79, 35, 48);
        internal static readonly Color DangerText = Color.FromArgb(253, 164, 175);

        internal static GraphicsPath RoundedPath(RectangleF bounds, float radius)
        {
            GraphicsPath path = new GraphicsPath();
            float diameter = Math.Min(radius * 2F, Math.Min(bounds.Width, bounds.Height));
            if (diameter <= 0F)
            {
                return path;
            }
            path.AddArc(bounds.Left, bounds.Top, diameter, diameter, 180F, 90F);
            path.AddArc(bounds.Right - diameter, bounds.Top, diameter, diameter, 270F, 90F);
            path.AddArc(bounds.Right - diameter, bounds.Bottom - diameter, diameter, diameter, 0F, 90F);
            path.AddArc(bounds.Left, bounds.Bottom - diameter, diameter, diameter, 90F, 90F);
            path.CloseFigure();
            return path;
        }
    }

    internal sealed class RoundedPanel : Panel
    {
        internal Color SurfaceColor = LauncherTheme.Surface;
        internal Color BorderColor = LauncherTheme.Line;

        internal RoundedPanel()
        {
            SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint |
                ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw |
                ControlStyles.SupportsTransparentBackColor, true);
            BackColor = Color.Transparent;
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            base.OnPaint(e);
            if (ClientSize.Width < 2 || ClientSize.Height < 2)
            {
                return;
            }
            e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            using (GraphicsPath shape = LauncherTheme.RoundedPath(new RectangleF(0.5F, 0.5F, Width - 1F, Height - 1F), 14F))
            using (SolidBrush fill = new SolidBrush(SurfaceColor))
            using (Pen border = new Pen(BorderColor))
            {
                e.Graphics.FillPath(fill, shape);
                e.Graphics.DrawPath(border, shape);
            }
        }
    }

    internal sealed class DashboardButton : Button
    {
        internal Color FillColor = LauncherTheme.Subtle;
        private bool _hovered;

        internal DashboardButton()
        {
            SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint |
                ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw |
                ControlStyles.SupportsTransparentBackColor, true);
            FlatStyle = FlatStyle.Flat;
            FlatAppearance.BorderSize = 0;
            BackColor = Color.Transparent;
            ForeColor = LauncherTheme.Ink;
            Cursor = Cursors.Hand;
            UseVisualStyleBackColor = false;
        }

        protected override void OnMouseEnter(EventArgs e)
        {
            _hovered = true;
            Invalidate();
            base.OnMouseEnter(e);
        }

        protected override void OnMouseLeave(EventArgs e)
        {
            _hovered = false;
            Invalidate();
            base.OnMouseLeave(e);
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            if (ClientSize.Width < 2 || ClientSize.Height < 2)
            {
                return;
            }
            e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            Color fill = !Enabled ? LauncherTheme.Subtle : (_hovered ? ControlPaint.Light(FillColor, 0.12F) : FillColor);
            using (GraphicsPath shape = LauncherTheme.RoundedPath(new RectangleF(0.5F, 0.5F, Width - 1F, Height - 1F), 9F))
            using (SolidBrush brush = new SolidBrush(fill))
            using (Pen border = new Pen(Focused && ShowFocusCues ? LauncherTheme.PrimaryLight : LauncherTheme.Line))
            {
                e.Graphics.FillPath(brush, shape);
                e.Graphics.DrawPath(border, shape);
            }
            TextFormatFlags flags = TextFormatFlags.VerticalCenter | TextFormatFlags.SingleLine | TextFormatFlags.EndEllipsis;
            flags |= TextAlign == ContentAlignment.MiddleLeft ? TextFormatFlags.Left : TextFormatFlags.HorizontalCenter;
            TextRenderer.DrawText(e.Graphics, Text, Font, new Rectangle(12, 0, Math.Max(1, Width - 24), Height),
                Enabled ? ForeColor : LauncherTheme.Muted, flags);
        }
    }

    internal sealed class ActivityBar : Control
    {
        private readonly System.Windows.Forms.Timer _timer;
        private float _offset;
        private bool _busy;
        private Color _accent = LauncherTheme.Primary;

        internal ActivityBar()
        {
            SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint |
                ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw |
                ControlStyles.SupportsTransparentBackColor, true);
            BackColor = Color.Transparent;
            TabStop = false;
            _timer = new System.Windows.Forms.Timer();
            _timer.Interval = 32;
            _timer.Tick += delegate
            {
                _offset = (_offset + 1.5F) % 100F;
                Invalidate();
            };
        }

        internal void SetActivity(bool busy, Color accent)
        {
            _busy = busy;
            _accent = accent;
            _timer.Enabled = busy;
            Invalidate();
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            base.OnPaint(e);
            if (ClientSize.Width < 2 || ClientSize.Height < 2)
            {
                return;
            }
            e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            using (GraphicsPath track = LauncherTheme.RoundedPath(new RectangleF(0F, 0F, Width, Height), Height / 2F))
            using (SolidBrush background = new SolidBrush(LauncherTheme.Subtle))
            {
                e.Graphics.FillPath(background, track);
                GraphicsState state = e.Graphics.Save();
                e.Graphics.SetClip(track);
                if (_busy)
                {
                    // A moving pulse indicates activity; its position is not a completion percentage.
                    float pulseWidth = Math.Max(72F, Width * 0.24F);
                    RectangleF pulse = new RectangleF((Width + pulseWidth) * _offset / 100F - pulseWidth, 0F, pulseWidth, Height);
                    using (LinearGradientBrush light = new LinearGradientBrush(pulse, _accent, LauncherTheme.Accent, 0F))
                    {
                        e.Graphics.FillRectangle(light, pulse);
                    }
                }
                else
                {
                    using (SolidBrush settled = new SolidBrush(Color.FromArgb(110, _accent)))
                    {
                        e.Graphics.FillPath(settled, track);
                    }
                }
                e.Graphics.Restore(state);
            }
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing)
            {
                _timer.Dispose();
            }
            base.Dispose(disposing);
        }
    }

    internal sealed class PortOwner
    {
        internal PortOwner(int processId, string processName, long startTimeUtcTicks)
        {
            ProcessId = processId;
            ProcessName = processName;
            StartTimeUtcTicks = startTimeUtcTicks;
        }

        internal int ProcessId { get; private set; }
        internal string ProcessName { get; private set; }
        internal long StartTimeUtcTicks { get; private set; }
    }

    internal sealed class DockerPortOwner
    {
        internal DockerPortOwner(string id, string name)
        {
            Id = id;
            Name = name;
        }

        internal string Id { get; private set; }
        internal string Name { get; private set; }
    }

    internal sealed class PortUsage
    {
        internal PortUsage(int port, bool isOccupied, List<PortOwner> processOwners)
        {
            Port = port;
            IsOccupied = isOccupied;
            ProcessOwners = processOwners ?? new List<PortOwner>();
            DockerOwners = new List<DockerPortOwner>();
        }

        internal int Port { get; private set; }
        internal bool IsOccupied { get; private set; }
        internal bool IsRequired { get; set; }
        internal string Description { get; set; }
        internal List<PortOwner> ProcessOwners { get; private set; }
        internal List<DockerPortOwner> DockerOwners { get; private set; }
    }

    internal static class PortInspector
    {
        private const int DefaultFrontendPort = 3001;

        internal static int ResolvePreferredPort(string backendDirectory)
        {
            int port;
            if (TryReadEnvironmentPort(EnvironmentVariableTarget.Process, out port) ||
                TryReadEnvironmentPort(EnvironmentVariableTarget.User, out port) ||
                TryReadEnvironmentPort(EnvironmentVariableTarget.Machine, out port))
            {
                return port;
            }

            string envPath = Path.Combine(backendDirectory, ".env");
            if (TryReadEnvFilePort(envPath, out port))
            {
                return port;
            }

            return DefaultFrontendPort;
        }

        internal static int FindAlternativePort()
        {
            for (int port = 3002; port <= 3010; port++)
            {
                if (IsAvailable(port))
                {
                    return port;
                }
            }

            return 0;
        }

        internal static PortUsage Inspect(int port)
        {
            bool isOccupied = !IsAvailable(port);
            List<PortOwner> owners = isOccupied ? FindListeningOwners(port) : new List<PortOwner>();
            return new PortUsage(port, isOccupied, owners);
        }

        internal static bool IsAvailable(int port)
        {
            if (port < 1 || port > 65535)
            {
                return false;
            }

            TcpListener listener = null;
            try
            {
                listener = new TcpListener(IPAddress.Loopback, port);
                listener.Server.ExclusiveAddressUse = true;
                listener.Start(1);
                return true;
            }
            catch (SocketException)
            {
                return false;
            }
            catch (UnauthorizedAccessException)
            {
                return false;
            }
            finally
            {
                if (listener != null)
                {
                    listener.Stop();
                }
            }
        }

        private static List<PortOwner> FindListeningOwners(int port)
        {
            List<PortOwner> owners = new List<PortOwner>();
            HashSet<int> processIds = new HashSet<int>();
            try
            {
                string netstatPath = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.System),
                    "netstat.exe"
                );
                ProcessStartInfo startInfo = new ProcessStartInfo();
                startInfo.FileName = netstatPath;
                startInfo.Arguments = "-ano -p tcp";
                startInfo.UseShellExecute = false;
                startInfo.CreateNoWindow = true;
                startInfo.RedirectStandardOutput = true;

                using (Process process = Process.Start(startInfo))
                {
                    if (process == null)
                    {
                        return owners;
                    }

                    string output = process.StandardOutput.ReadToEnd();
                    process.WaitForExit();
                    foreach (string line in output.Split(new string[] { "\r\n", "\n" }, StringSplitOptions.RemoveEmptyEntries))
                    {
                        string[] parts = line.Trim().Split(new char[] { ' ', '\t' }, StringSplitOptions.RemoveEmptyEntries);
                        if (parts.Length < 5 || !string.Equals(parts[0], "TCP", StringComparison.OrdinalIgnoreCase))
                        {
                            continue;
                        }

                        int localPort;
                        if (!TryParseEndpointPort(parts[1], out localPort) || localPort != port)
                        {
                            continue;
                        }

                        bool isListening = string.Equals(parts[3], "LISTENING", StringComparison.OrdinalIgnoreCase) ||
                            parts[2].EndsWith(":0", StringComparison.Ordinal);
                        int processId;
                        if (!isListening || !int.TryParse(parts[parts.Length - 1], out processId) || !processIds.Add(processId))
                        {
                            continue;
                        }

                        owners.Add(ResolveProcessOwner(processId));
                    }
                }
            }
            catch
            {
                // Occupancy is still reported even when process ownership cannot be read.
            }

            return owners;
        }

        private static bool TryParseEndpointPort(string endpoint, out int port)
        {
            port = 0;
            if (string.IsNullOrEmpty(endpoint))
            {
                return false;
            }

            int separator = endpoint.LastIndexOf(':');
            return separator >= 0 && separator < endpoint.Length - 1 &&
                int.TryParse(endpoint.Substring(separator + 1), out port);
        }

        private static PortOwner ResolveProcessOwner(int processId)
        {
            try
            {
                using (Process process = Process.GetProcessById(processId))
                {
                    long startTimeUtcTicks = 0;
                    try
                    {
                        startTimeUtcTicks = process.StartTime.ToUniversalTime().Ticks;
                    }
                    catch
                    {
                        // Some protected processes do not expose their start time.
                    }
                    return new PortOwner(processId, process.ProcessName, startTimeUtcTicks);
                }
            }
            catch
            {
                return new PortOwner(processId, "未知进程", 0);
            }
        }

        private static bool TryReadEnvironmentPort(EnvironmentVariableTarget target, out int port)
        {
            port = 0;
            try
            {
                return TryParsePort(Environment.GetEnvironmentVariable("FRONTEND_PORT", target), out port);
            }
            catch
            {
                return false;
            }
        }

        private static bool TryReadEnvFilePort(string envPath, out int port)
        {
            port = 0;
            if (!File.Exists(envPath))
            {
                return false;
            }

            try
            {
                foreach (string rawLine in File.ReadAllLines(envPath, new UTF8Encoding(false, true)))
                {
                    string line = rawLine.Trim().TrimStart('\uFEFF');
                    if (line.Length == 0 || line.StartsWith("#", StringComparison.Ordinal))
                    {
                        continue;
                    }
                    if (line.StartsWith("export ", StringComparison.OrdinalIgnoreCase))
                    {
                        line = line.Substring(7).TrimStart();
                    }

                    int equalsIndex = line.IndexOf('=');
                    if (equalsIndex <= 0 || !string.Equals(
                        line.Substring(0, equalsIndex).Trim(),
                        "FRONTEND_PORT",
                        StringComparison.OrdinalIgnoreCase))
                    {
                        continue;
                    }

                    string value = line.Substring(equalsIndex + 1).Trim().Trim('"', '\'');
                    return TryParsePort(value, out port);
                }
            }
            catch
            {
                return false;
            }

            return false;
        }

        private static bool TryParsePort(string value, out int port)
        {
            return int.TryParse((value ?? string.Empty).Trim(), out port) && port >= 1 && port <= 65535;
        }
    }

    internal sealed class CommandResult
    {
        internal CommandResult(int exitCode, bool timedOut, string output)
        {
            ExitCode = exitCode;
            TimedOut = timedOut;
            Output = output ?? string.Empty;
        }

        internal int ExitCode { get; private set; }
        internal bool TimedOut { get; private set; }
        internal string Output { get; private set; }
    }

    internal sealed class KeyMaterial
    {
        internal KeyMaterial(string value, string source)
        {
            Value = value;
            Source = source;
        }

        internal string Value { get; private set; }
        internal string Source { get; private set; }
    }

    internal static class ApiKeyResolver
    {
        private const string VariableName = "DEEPSEEK_API_KEY";

        internal static KeyMaterial Resolve(string backendDirectory)
        {
            KeyMaterial processValue = FromEnvironment(EnvironmentVariableTarget.Process, "进程环境变量");
            if (processValue != null)
            {
                return processValue;
            }

            KeyMaterial userValue = FromEnvironment(EnvironmentVariableTarget.User, "用户环境变量");
            if (userValue != null)
            {
                return userValue;
            }

            KeyMaterial machineValue = FromEnvironment(EnvironmentVariableTarget.Machine, "系统环境变量");
            if (machineValue != null)
            {
                return machineValue;
            }

            string envFile = Path.Combine(backendDirectory, ".env");
            string fileValue = ReadValueFromEnvFile(envFile);
            if (IsUsable(fileValue))
            {
                return new KeyMaterial(fileValue.Trim(), "dk-ai-agent\\.env");
            }

            return null;
        }

        private static KeyMaterial FromEnvironment(EnvironmentVariableTarget target, string source)
        {
            try
            {
                string value = Environment.GetEnvironmentVariable(VariableName, target);
                return IsUsable(value) ? new KeyMaterial(value.Trim(), source) : null;
            }
            catch
            {
                return null;
            }
        }

        private static string ReadValueFromEnvFile(string envFile)
        {
            if (!File.Exists(envFile))
            {
                return null;
            }

            string[] lines;
            try
            {
                lines = File.ReadAllLines(envFile, new UTF8Encoding(false, true));
            }
            catch
            {
                return null;
            }

            foreach (string rawLine in lines)
            {
                string line = rawLine.Trim().TrimStart('\uFEFF');
                if (line.Length == 0 || line.StartsWith("#", StringComparison.Ordinal))
                {
                    continue;
                }

                if (line.StartsWith("export ", StringComparison.OrdinalIgnoreCase))
                {
                    line = line.Substring(7).TrimStart();
                }

                int equalsIndex = line.IndexOf('=');
                if (equalsIndex <= 0)
                {
                    continue;
                }

                string name = line.Substring(0, equalsIndex).Trim();
                if (!string.Equals(name, VariableName, StringComparison.OrdinalIgnoreCase))
                {
                    continue;
                }

                string value = line.Substring(equalsIndex + 1).Trim();
                if (value.Length >= 2)
                {
                    bool doubleQuoted = value[0] == '"' && value[value.Length - 1] == '"';
                    bool singleQuoted = value[0] == '\'' && value[value.Length - 1] == '\'';
                    if (doubleQuoted || singleQuoted)
                    {
                        value = value.Substring(1, value.Length - 2);
                    }
                }

                return value;
            }

            return null;
        }

        private static bool IsUsable(string value)
        {
            if (string.IsNullOrWhiteSpace(value))
            {
                return false;
            }

            string normalized = value.Trim();
            return !normalized.StartsWith("replace-", StringComparison.OrdinalIgnoreCase)
                && !string.Equals(normalized, "sk-xxxx", StringComparison.OrdinalIgnoreCase)
                && !normalized.StartsWith("apply for", StringComparison.OrdinalIgnoreCase);
        }
    }

    internal sealed class LauncherPaths
    {
        private LauncherPaths()
        {
        }

        internal string ProjectRoot { get; private set; }
        internal string BackendDirectory { get; private set; }
        internal string ComposeFile { get; private set; }
        internal string DockerCli { get; private set; }
        internal string DockerDesktop { get; private set; }
        internal string BrowserExecutable { get; private set; }

        internal static LauncherPaths Discover()
        {
            string projectRoot = FindProjectRoot();
            if (string.IsNullOrEmpty(projectRoot))
            {
                throw new DirectoryNotFoundException(
                    "没有找到项目根目录。请把本程序保留在项目的 launcher 目录中。"
                );
            }

            LauncherPaths paths = new LauncherPaths();
            paths.ProjectRoot = projectRoot;
            paths.BackendDirectory = Path.Combine(projectRoot, "dk-ai-agent");
            paths.ComposeFile = Path.Combine(paths.BackendDirectory, "docker-compose.yml");
            paths.DockerCli = FindDockerCli();
            paths.DockerDesktop = FindDockerDesktop();
            paths.BrowserExecutable = FindBrowser();

            if (!File.Exists(paths.ComposeFile))
            {
                throw new FileNotFoundException("没有找到 dk-ai-agent\\docker-compose.yml。", paths.ComposeFile);
            }

            if (string.IsNullOrEmpty(paths.DockerCli))
            {
                throw new FileNotFoundException("没有找到 docker.exe。请安装 Docker Desktop 并重新打开启动器。");
            }

            if (string.IsNullOrEmpty(paths.BrowserExecutable))
            {
                throw new FileNotFoundException("没有找到 Microsoft Edge 或 Google Chrome。");
            }

            return paths;
        }

        private static string FindProjectRoot()
        {
            DirectoryInfo cursor = new DirectoryInfo(AppDomain.CurrentDomain.BaseDirectory);
            for (int depth = 0; cursor != null && depth < 8; depth++)
            {
                string composeFile = Path.Combine(cursor.FullName, "dk-ai-agent", "docker-compose.yml");
                if (File.Exists(composeFile))
                {
                    return cursor.FullName;
                }

                cursor = cursor.Parent;
            }

            return null;
        }

        private static string FindDockerCli()
        {
            string programFiles = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles);
            string known = Path.Combine(programFiles, "Docker", "Docker", "resources", "bin", "docker.exe");
            if (File.Exists(known))
            {
                return known;
            }

            return FindOnPath("docker.exe");
        }

        private static string FindDockerDesktop()
        {
            string programFiles = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles);
            string known = Path.Combine(programFiles, "Docker", "Docker", "Docker Desktop.exe");
            return File.Exists(known) ? known : null;
        }

        private static string FindBrowser()
        {
            string programFiles = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles);
            string programFilesX86 = Environment.GetEnvironmentVariable("ProgramFiles(x86)") ?? string.Empty;
            string localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            string[] candidates = new string[]
            {
                Path.Combine(programFilesX86, "Microsoft", "Edge", "Application", "msedge.exe"),
                Path.Combine(programFiles, "Microsoft", "Edge", "Application", "msedge.exe"),
                Path.Combine(localAppData, "Microsoft", "Edge", "Application", "msedge.exe"),
                Path.Combine(programFiles, "Google", "Chrome", "Application", "chrome.exe"),
                Path.Combine(programFilesX86, "Google", "Chrome", "Application", "chrome.exe"),
                Path.Combine(localAppData, "Google", "Chrome", "Application", "chrome.exe")
            };

            foreach (string candidate in candidates)
            {
                if (!string.IsNullOrEmpty(candidate) && File.Exists(candidate))
                {
                    return candidate;
                }
            }

            return null;
        }

        private static string FindOnPath(string fileName)
        {
            string pathValue = Environment.GetEnvironmentVariable("PATH") ?? string.Empty;
            foreach (string rawDirectory in pathValue.Split(Path.PathSeparator))
            {
                string directory = rawDirectory.Trim().Trim('"');
                if (directory.Length == 0)
                {
                    continue;
                }

                try
                {
                    string candidate = Path.Combine(directory, fileName);
                    if (File.Exists(candidate))
                    {
                        return candidate;
                    }
                }
                catch
                {
                    // Ignore malformed PATH entries.
                }
            }

            return null;
        }
    }
}
