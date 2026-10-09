# Windows 桌面客户端与本地服务端

桌面版采用两个独立安装程序：`PsychCounselorClient-0.1.0.exe` 和 `PsychCounselorServer-0.1.0.exe`。两者按当前 Windows 用户安装，各有独立程序目录、快捷方式、开始菜单分组与升级标识。用户无需管理员权限，也无需自行安装 Java、Node、Python、Docker 或数据库。

Client 是独立 JVM；Server 管理窗口、托盘与 Spring API 在同一 JVM 中运行，另启动隐藏 Python Worker sidecar。两包同时运行且 Worker 成功启动时共有 3 个 OS 进程；Worker 不可用时保留两个 JVM 并使用 Java fallback。

本机使用顺序：

1. 安装并双击 `PsychCounselorServer.exe`。首次启动保存随机生成的 `admin` 密码，在服务管理窗口选择是否登录自启动；DeepSeek API Key 可按需要配置，由当前 Windows 用户的 DPAPI 加密保存。
2. 启动服务后，安装并双击 `PsychCounselorClient.exe`，使用本机连接。服务端只在 `127.0.0.1` 随机端口提供 API；客户端从当前用户的端点文件发现服务，不启动或嵌入 Spring。
3. Client 使用随包 Vue，通过自己的回环 UI/受限代理与 Server 交互。选择远程服务时，仍使用随包 UI，API 发往所配置的服务器；远程连接需要 HTTPS，标准回环地址允许 HTTP。
4. 关闭 Client 只退出客户端、取消其请求，Server 继续在托盘运行。关闭服务管理窗口会收起到托盘，在 Server 托盘选择“退出服务”才停止后台服务。托盘不可用时需保留服务管理窗口。

未配置模型 Key 时可以登录和查看已有记录；聊天及联网搜索需要有效 Key 和网络。自包含安装包包含程序与运行依赖，不等于云端模型可以离线运行。服务端可通过当前用户的 HKCU Run 登录自启动，启动命令指向 `PsychCounselorServer.exe --background`，无需管理员权限；Client 与 Server 的安装目录不能被手动改成同一个目录。

当前用户目录为 `%LOCALAPPDATA%\PsychCounselor`：`client.properties` 保存 UTF-8 连接偏好；`server-endpoint.properties` 只保存本机端点、实例 ID 和 `protocol.version`，不保存凭据；服务端拥有 `data` 中的加密存储、DPAPI 凭据和本地运行锁。客户端读取端点不等于服务已经就绪，必须通过就绪检查确认。不要把该目录、日志或备份复制到发行包，也不要直接将绑定 Windows 用户的加密数据搬到另一个账号。

服务管理窗口提供“创建加密备份…”和“验证备份（隔离恢复）…”：创建冷备先停止 Spring、确认关闭后再停止 Worker，完成后重新启动两者；验证只写入新的 UUID 隔离目录。备份限同一台 Windows 电脑、同一用户使用，已有文件不会覆盖；没有正式数据覆盖恢复入口。操作步骤、空间限制和解密边界见 [服务端数据备份说明](../deploy/desktop/server-data-backup.md)。

## 构建与包内容

Windows x64 构建机需要 PowerShell 7 与完整 JDK 25（包括 `jar`、`jlink`、`jpackage`、`jmods`）。Client 另需 Node/npm，版本按前端 engines；Server 另需 Python 3.12+，只用于在构建机冻结 Worker。EXE 安装程序还需要与 JDK 25 jpackage 兼容的 WiX。Maven Wrapper、npm 与 pip 下载依赖需要网络。

在仓库根目录执行：

```powershell
pwsh -NoProfile -File .\deploy\desktop\build-desktop.ps1 -Product Both -Installer -JdkHome 'C:\Path\To\jdk-25' -PythonExecutable 'C:\Path\To\Python312\python.exe'
```

`-Product Client` 和 `-Product Server` 可独立构建；不加 `-Installer` 时生成各自的完整 app-image。默认输出位于 `release/desktop/client` 与 `release/desktop/server`。已有同名产品目录时停止，重新构建可用 `-OutputDirectory` 指定新的发行根目录。分发 app-image 必须复制完整的 `PsychCounselorClient` 或 `PsychCounselorServer` 目录。

Client JAR 包含 JavaFX 窗口、端点发现、受限代理和 `/static` 静态 Vue，首页为 `/static/index.html`、构建资源位于 `/static/assets/`。客户端将页面请求映射到首页，将 `/assets/...` 请求映射到 `/static/assets/...`，与打包目录一致；不含本地后端、DPAPI 密钥存储、Python Worker、Spring 依赖或业务数据库。Server JAR 包含管理器、托盘、`WorkerSidecar`、DPAPI 存储和备份类，后端类与静态配置另置于 `server-backend-classes.jar`；不包含 Vue 或 Client UI/代理代码。脚本从同一桌面模块编译结果按类白名单拆出两个 JAR，内部类跟随各自顶层类；不会直接将包含全部类的 Maven JAR 复制进发行包。

Server 随包有冻结的 Worker sidecar，位置固定为 `app/worker/PsychCounselorWorker.exe`，其 `_internal` 目录包含必要 Python 运行依赖，构建 venv 不进发行包。`WorkerSidecar` 从 Server 安装位置解析固定路径并隐藏启动，不接受用户提供的可执行文件或命令。冻结入口读取 `AI_WORKER_PORT`，只绑定 `127.0.0.1` 随机端口，不读取 `.env`。每次启动生成随机 `AI_WORKER_SHARED_SECRET`，与 `DEEPSEEK_API_KEY` 仅经子进程环境传递，不写入命令行、端点或日志。就绪检查核对返回的 PID 与所启动进程一致，成功后才开启 Java Worker 调用；缺失或启动失败时降级 Java fallback，意外退出后关闭调用门禁。停服先确认 Spring 关闭，再请求 Worker 停止并确认进程退出。

每个产品的 `build-manifest.json` 记录 `product`、`protocolVersion`、`processArchitecture`、版本、入口、模块、白名单及包内文件哈希，不记录运行时模型密钥、管理员密码、数据库密码或业务数据。Client 的 `processArchitecture` 为 `desktop-client-jvm`；Server 为 `spring-server-jvm-with-python-worker-sidecar`，并记录 `workerBundled=true`、`workerAutoStartIntegrated=true`，这些字段描述包内容与实现接线，不等于完整 Server 管理的运行生命周期验收。Client 与 Server 都包含内嵌 Java 25/JavaFX 运行时及 `jdk.httpserver`；Server 按 Spring/JDBC/JNA 所需添加命名、SQL、管理、反射和服务提供模块，Client 不打包后端依赖。

完整参数与发行目录约定见 [Windows 桌面发行包](../deploy/desktop/README.md)。

## 发布验收边界

打包脚本跳过测试；Windows CI 配置独立执行 desktop-client 单测、PowerShell 语法检查、两个 app-image 构建和包内容边界检查。本轮已知本地结果为桌面单元测试 **121 passed**；Worker 全量测试 **49 passed**（其中 API **21 passed**）；后端 **509 tests：494 passed、15 skipped、0 failures/errors**（含 `AiWorkerClient` 相关测试）；eval **123 passed、20 subtests passed**。skipped 不计为执行通过。已验证的 Client app-image 与 EXE 安装程序位于 `release/desktop-installers-20261009/client`，manifest 所列 3 个文件的哈希核对通过；最新重建的 Server app-image 与 EXE 安装程序位于 `release/desktop-installers-20261009-workerfix/server`，manifest 所列 301 个文件的哈希全部匹配，Server EXE 为 **287581696 bytes**。Client/Server 包内容边界检查通过。最新 Server 包内冻结 Worker EXE 的独立本机 smoke test 已通过 readiness PID 校验，使用随机 token 请求 shutdown 返回 **202**，并确认进程退出。安装/卸载、未预装依赖的干净用户环境、GUI/托盘、Client–Server 端到端与完整 Server 管理的 Worker 生命周期尚未验收；DPAPI 单元测试已通过，整包安装后的实际凭据保存与读取交互尚未验收。远端 CI 未验证，不能据此认定桌面发布验收通过。

发布前需要在未安装 Java/Node/Python/Docker 的 Windows x64 普通用户环境检查：两个安装程序可以共存并独立升级；首次密码和模型 Key 保存；服务管理器收起和登录自启动；Client 登录、Cookie/CSRF、聊天流和取消请求；Client 退出后 Server 仍可供重新打开的 Client 使用；Server 退出释放数据库、端点与锁；远程模式使用随包 UI；备份隔离恢复不会修改生产数据。还需验收 Worker 隐藏启动、PID 回环就绪、共享密钥拒绝、模型 Key 传递、意外退出降级、Spring 后 Worker 的停止顺序和备份后重启。

本轮已运行上述桌面、Worker、后端与 eval 测试；对应命令如下，从仓库根目录开始。Worker API 的 21 项包含在 Worker 全量结果中；指定 Worker/eval 临时目录是为了避开 Windows 沙箱临时目录 ACL 限制。单元测试不能证明实际打包运行时与 Windows 安装交互通过：

```powershell
cd desktop-client
.\mvnw.cmd -B test

cd ..\ai-worker
.\.venv\Scripts\python.exe -m pytest tests -q --basetemp ..\.pytest-runtime\worker-full-20261009

cd ..\dk-ai-agent
.\mvnw.cmd -B test

cd ..
$env:TEMP = Join-Path (Get-Location) '.pytest-runtime\eval-temp-20261009'
$env:TMP = $env:TEMP
.\ai-worker\.venv\Scripts\python.exe -m pytest eval -q --basetemp .pytest-runtime/eval-temp-20261009/pytest-basetemp
```
