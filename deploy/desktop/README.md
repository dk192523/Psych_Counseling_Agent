# Windows 桌面发行包

桌面版分为两个独立、按当前 Windows 用户安装的 EXE 安装程序：

| 产品 | 程序 | 包内职责 |
| --- | --- | --- |
| Client | `PsychCounselorClient.exe` | JavaFX/WebView、随包静态 Vue、回环 HTTP UI 和受限 API 代理 |
| Server | `PsychCounselorServer.exe` | JavaFX 服务管理窗口、后台托盘、Spring API、加密存储、冻结 Python Worker sidecar |

两包独立安装：Client 一个 JVM，Server 管理窗口、托盘与 Spring API 共用一个 JVM，Server 另启动隐藏 Python Worker sidecar。两包同时运行且 Worker 成功启动时共有 3 个 OS 进程；Worker 不可用时降级 Java fallback。本机服务默认只监听 `127.0.0.1`，使用随机端口；客户端读取当前用户的端点文件，并在客户端自己的随机回环端口提供随包 UI，也可配置 HTTPS 远程服务地址。关闭客户端只关闭客户端连接和请求，服务端继续在托盘运行。关闭服务管理窗口会收起到托盘；在服务端托盘选择“退出服务”才会停止服务。系统托盘不可用时应保留服务管理窗口。

用户先安装并启动 Server，在管理窗口保存首次生成的管理员密码，按需要填写模型 API Key，再启动 Client。用户无需终端、Docker、Java、Node、Python 或管理员权限。模型聊天仍需要有效云端 API Key 与网络。按当前用户安装的路径、EXE 名、开始菜单分组、快捷方式和升级 UUID 均按产品隔离。

构建机需要 Windows x64、PowerShell 7、完整 JDK 25。Client 构建另需符合前端 engines 的 Node/npm；Server 构建另需 Python 3.12+，用于在临时 venv 中安装 Worker 依赖并用 PyInstaller 冻结。生成 EXE 安装程序还需与 JDK 25 `jpackage` 兼容的 WiX 工具集。构建依赖下载需要网络。

从仓库根目录执行：

```powershell
# 两个独立 EXE 安装程序，以及各自完整 app-image。
pwsh -NoProfile -File .\deploy\desktop\build-desktop.ps1 -Product Both -Installer -JdkHome 'C:\Path\To\jdk-25' -PythonExecutable 'C:\Path\To\Python312\python.exe'

# 可分别构建；Server 不构建 Vue、不要求 Node。
pwsh -NoProfile -File .\deploy\desktop\build-desktop.ps1 -Product Client -JdkHome 'C:\Path\To\jdk-25'
pwsh -NoProfile -File .\deploy\desktop\build-desktop.ps1 -Product Server -JdkHome 'C:\Path\To\jdk-25' -PythonExecutable 'C:\Path\To\Python312\python.exe'
```

默认输出为：

```text
release/desktop/
  client/
    build-manifest.json
    PsychCounselorClient-0.1.0.exe       # 加 -Installer 时生成
    PsychCounselorClient/
      PsychCounselorClient.exe
      app/psych-counselor-client.jar    # 内含 static/index.html、static/assets/
      app/lib/desktop/                 # JNA
      runtime/
  server/
    build-manifest.json
    PsychCounselorServer-0.1.0.exe       # 加 -Installer 时生成
    PsychCounselorServer/
      PsychCounselorServer.exe
      app/psych-counselor-server.jar
      app/server-backend-classes.jar   # API 与静态配置，不含 Vue
      app/lib/desktop/                 # JNA
      app/lib/backend/                 # Spring/JDBC/ONNX 等运行依赖
      app/worker/PsychCounselorWorker.exe
      app/worker/_internal/            # 冻结 Worker 的 Python 运行依赖
      runtime/
```

不加 `-Installer` 时只生成 app-image，必须整体分发产品目录，不能只复制 EXE。`-Version` 设置三段版本号。`-OutputDirectory` 指定发行根目录，脚本在其下创建 `client`/`server` 子目录；选中的产品目录已经存在时会停止，不覆盖、不递归删除。脚本拒绝源码目录、磁盘根目录及经过符号链接/目录联接的输出路径。Client 与 Server 可依次构建到同一发行根目录；重新构建已有产品应选择新的根目录。原 `-Mode Local/Remote` 已由 `-Product Client/Server/Both` 取代，Client 在运行时选择本机或远程服务。

打包从 `desktop-client` 编译类按产品白名单生成两个 JAR，包含各自的内部类；服务端白名单包含 `LocalDataBackup` 与 `WorkerSidecar`。Client 不带 Spring、Worker、模型密钥或业务数据库；Server 不带 Vue、客户端 WebView/代理类、模型密钥或业务数据。后端资源只允许编译后的业务类、`application.yml`、`application-local-desktop.yml`、内置 `document/` 和 `exam/`；不复制个人 profile、MCP 配置、`.env`、用户数据、备份和日志。模型密钥和数据库凭据由服务端在运行时生成或接收，构建清单记录产品、`protocolVersion`、`processArchitecture`、运行模块及文件哈希；Client 架构值为 `desktop-client-jvm`，Server 为 `spring-server-jvm-with-python-worker-sidecar`。两个 jlink 运行时均显式包含 `jdk.httpserver`。

Worker 是单独的 sidecar，使用 `--onedir --noconsole` 冻结；只将冻结输出复制到 `app/worker`，不打包构建 venv、源码 `.env`、咨询原始资料或开发机凭据。`WorkerSidecar` 只从 Server 安装目录的固定 `app/worker/PsychCounselorWorker.exe` 隐藏启动 Worker。冻结入口读取 `AI_WORKER_PORT`，只绑定 `127.0.0.1` 随机端口，不读取工作目录 `.env`；随机 `AI_WORKER_SHARED_SECRET` 和模型 `DEEPSEEK_API_KEY` 只在启动时经子进程环境传递，既不写清单也不写命令行或端点文件。就绪响应必须匹配启动进程 PID，成功后才启用 Worker；缺失、启动失败或意外退出时降级 Java fallback。停服先确认 Spring 关闭，再停止并确认 Worker 退出；冷备完成后重新启动两者。Server 清单的 `workerBundled=true` 与 `workerAutoStartIntegrated=true` 描述工件和自动生命周期接线，不代表完整 Server 管理的 Worker 生命周期已验收。

Windows CI 配置包含 desktop-client 单测、构建脚本语法检查、两个 app-image 构建和包内容边界检查。本轮已知本地结果为桌面单元测试 **121 passed**；Worker 全量测试 **49 passed**（其中 API **21 passed**）；后端 **509 tests：494 passed、15 skipped、0 failures/errors**（含 `AiWorkerClient` 相关测试）；eval **123 passed、20 subtests passed**；迁移预检本地合成测试 **29 passed**；Client/Server/Worker 协议兼容检查通过。skipped 不计为执行通过。已验证的 Client app-image 与 EXE 安装程序位于 `release/desktop-installers-20261009/client`，manifest 所列 3 个文件的哈希核对通过；最新重建的 Server app-image 与 EXE 安装程序位于 `release/desktop-installers-20261009-workerfix/server`，manifest 所列 301 个文件的哈希全部匹配，Server EXE 为 **287581696 bytes**。Client/Server 包内容边界检查通过。最新 Server 包内冻结 Worker EXE 的独立本机 smoke test 已通过 readiness PID 校验，使用随机 token 请求 shutdown 返回 **202**，并确认进程退出。安装/卸载、未预装依赖的干净用户环境、GUI/托盘、Client–Server 端到端与完整 Server 管理的 Worker 生命周期尚未验收；DPAPI 单元测试已通过，整包安装后的实际凭据保存与读取交互尚未验收。远端 CI 未验证，不能据此认定桌面发布验收通过。

本轮上述测试已执行。对应命令：在 `desktop-client` 中运行 `.\mvnw.cmd -B test`；在 `ai-worker` 中运行 `.\.venv\Scripts\python.exe -m pytest tests -q --basetemp ..\.pytest-runtime\worker-full-20261009`（API 21 项包含在全量结果中）；在 `dk-ai-agent` 中运行 `.\mvnw.cmd -B test`；在仓库根目录将 `TEMP`/`TMP` 指向 `.pytest-runtime/eval-temp-20261009`，再运行 `.\ai-worker\.venv\Scripts\python.exe -m pytest eval -q --basetemp .pytest-runtime/eval-temp-20261009/pytest-basetemp`。Worker/eval 临时目录参数用于避开 Windows 沙箱临时目录 ACL 限制。本轮本机打包已通过；安装交互仍待验收。

使用、数据边界与发布验收见 [desktop-client/README.md](../../desktop-client/README.md)。Server 管理窗口提供停服加密冷备和隔离只读验证，限同一 Windows 电脑、同一用户，不覆盖已有备份或正式数据；操作与格式说明见 [server-data-backup.md](server-data-backup.md)。
