# AI 心理咨询师

这是一个面向日常心理疏导的 RAG 应用，采用独立桌面客户端与服务端分离的架构。它先理解用户的感受并澄清事实，在信息不足时以简短提问为主；画像足够清晰后，会先复述确认并征得许可，再进行完整梳理。

Windows 桌面版默认使用两个独立、按当前用户安装的 Client 和 Server 安装包：Client 显示随包 Vue 页面，Server 管理窗口与 Spring API 在同一 JVM 中运行，并启动隐藏的 Python Worker sidecar。Client 与 Server 同时运行且 Worker 成功启动时共有 3 个 OS 进程。默认连接本机 `127.0.0.1` 随机端口，客户端也可配置 HTTPS 远程服务地址；咨询、检索、数据库和 DeepSeek API Key 均由服务端管理。

当前咨询检索分为案例参考（`psych-cases`）、专业知识（`psych-knowledge`）和政策材料（`psych-policy`）三个运行时域，按用户意图选域。公开连麦案例只用于检索参考和原文溯源，不能作为诊断、法规或危机处置的专业依据，也不定义 AI 的身份、立场或表达风格。项目不模仿、代表或绑定任何现实人物。

> 本项目仅供个人学习和体验，不是医疗服务，不能替代持证心理咨询师、精神科医生或紧急救援服务。服务端登录限流与会话注册表均为进程内状态，不支持多副本共享，因此服务端必须只运行一个实例。开放注册仅适合本机使用，不应将开放注册的配置直接部署到公网。

## 功能

- 单一产品入口：AI 心理咨询师。
- 友好简短的首次问候，不在用户开口前主动分析。
- 快速回复：Java `QuestionAnswerAdvisor` + 向量 RAG；桌面 Server 使用本地加密存储，Docker/云部署路径使用 PostgreSQL/pgvector。
- 深度思考：Java 25 并发主控 + 可选 Python Worker，执行查询规划、中文 BM25/RRF 重排和证据筛选；当前运行时不读取 raw 逐字稿。
- 自动回退：Python Worker 不可用时回退 Java Agent，再失败则回到基础 Java RAG。
- SSE 流式输出：展示真实的规划、检索、核对、组织回答和回退阶段，不展示隐藏思维链。
- 账号体系：注册/登录、24 小时会话、按用户隔离的会话与记忆、管理员后台（见"账号与数据隔离"）。
- 会话持久化：由服务端存储保存；页面支持新建、切换和删除。
- 安全 Markdown：AI 回复经过 Markdown 渲染和 HTML 消毒，用户输入按纯文本显示。
- 来源溯源：保留获准材料的来源信息与引用链接；历史逐字稿二级检索当前未接入服务路径。
- 独立模拟练习：登录后从咨询页顶栏进入 `/practice`，每轮加载 5 道题，提交选项、查看解析并重新开始；使用本地原创虚构材料，不进入向量库或咨询记忆，不持久化成绩。

## 技术结构

```text
Psych_Counseling_Agent/
├── dk-ai-agent/                  Java 25 后端、Vue 前端和可选 Docker Compose
├── ai-worker/                    Python 3.12 深度检索 Worker
├── counseling-kb/                历史案例材料、来源清单和离线治理校验脚本
├── docs/                         功能与技术文档
├── desktop-client/               Java 25 / JavaFX Client 与本地 Server 管理器
├── deploy/desktop/               Windows 两个独立安装包构建脚本
└── launcher/                     旧 Docker 服务版启动器源码
```

主要组件：

- Java 25、Spring Boot 3.5.16、Spring AI 1.0.9。
- Python 3.12、FastAPI、Pydantic、jieba、BM25/RRF。
- Vue 3、Vite、SSE、markdown-it、DOMPurify。
- Docker/云服务端 PostgreSQL 16 + pgvector；桌面 Server 本地加密存储；服务端本地 ONNX embedding、客户端 JavaFX 25 WebView。
- DeepSeek V4 Flash 文本生成与网页搜索。

## Windows 桌面 Client 与 Server

`desktop-client/` 是独立 Maven 项目，构建 Client 与 Server 两个产品。先安装并启动 `PsychCounselorServer.exe`，在管理窗口保存首次生成的管理员密码并按需要配置模型 Key，再启动 `PsychCounselorClient.exe`。客户端默认从当前用户的端点文件发现本机 Server，在自己的随机回环端口显示随包 Vue，通过受限代理调用 API；UTF-8 连接偏好保存到 `%LOCALAPPDATA%\PsychCounselor\client.properties`。

选择远程服务时仍显示随包 UI，API 发往配置的服务端；远程 origin 必须使用 HTTPS，HTTP 仅供 localhost、标准 IPv4 回环或 IPv6 回环地址使用。API Key、账号、会话、记忆、知识库和模型均由服务端管理。关闭 Client 不会停止 Server；关闭 Server 管理窗口会收起到托盘，选择“退出服务”才停服。

Server 固定从安装目录 `app/worker/PsychCounselorWorker.exe` 启动 Worker，只监听随机回环端口。随机共享 token 和模型 API Key 仅在启动时经子进程环境传递，不写入端点、构建清单或命令行；就绪检查核对 Worker PID。Worker 缺失、启动失败或意外退出时降级 Java fallback。创建加密冷备先停止 Spring、再停止 Worker，备份完成后重新启动两者；备份验证只做隔离恢复，不覆盖正式数据。

构建机需要 Windows x64、PowerShell 7 和含 jlink、jpackage、jmods 的完整 JDK 25；Client 构建需 Node/npm，Server 冻结 Worker 需 Python 3.12+，依赖下载需网络。仓库根目录执行：

```powershell
pwsh -NoProfile -File .\deploy\desktop\build-desktop.ps1 -Product Both -Installer -JdkHome 'C:\Path\To\jdk-25' -PythonExecutable 'C:\Path\To\Python312\python.exe'
```

默认输出为 `release/desktop/client` 与 `release/desktop/server`，安装程序分别为 `PsychCounselorClient-0.1.0.exe` 与 `PsychCounselorServer-0.1.0.exe`。不加 `-Installer` 只生成 app-image，分发时复制完整产品目录；用户无需安装 Java、Node、Python 或 Docker。app-image 无需 WiX，EXE 安装包需要兼容 JDK 25 jpackage 的 WiX；已有同名输出可用 `-OutputDirectory` 指向新目录。Server 类白名单包含 `WorkerSidecar`，清单 `processArchitecture` 分别记录 Client JVM 与 Spring Server JVM + Python Worker sidecar。详见 [桌面说明](desktop-client/README.md)和[构建说明](deploy/desktop/README.md)。

本轮已知本地结果：桌面单元测试 **121 passed**；Worker 全量测试 **49 passed**（其中 API **21 passed**）；后端 **509 tests：494 passed、15 skipped、0 failures/errors**（含 `AiWorkerClient` 相关测试）；eval **123 passed、20 subtests passed**；迁移预检本地合成测试 **29 passed**；Client/Server/Worker 协议兼容检查通过。skipped 不计为执行通过。已验证的 Client app-image 与 EXE 安装程序位于 `release/desktop-installers-20261009/client`，manifest 所列 3 个文件的哈希核对通过；最新重建的 Server app-image 与 EXE 安装程序位于 `release/desktop-installers-20261009-workerfix/server`，manifest 所列 301 个文件的哈希全部匹配，Server EXE 为 **287581696 bytes**。Client/Server 包内容边界检查通过。最新 Server 包内冻结 Worker EXE 的独立本机 smoke test 已通过 readiness PID 校验，使用随机 token 请求 shutdown 返回 **202**，并确认进程退出。安装/卸载、未预装依赖的干净用户环境、GUI/托盘、Client–Server 端到端与完整 Server 管理的 Worker 生命周期尚未验收；DPAPI 单元测试已通过，整包安装后的实际凭据保存与读取交互尚未验收。远端 CI 未验证，不能据此认定桌面发布验收通过。旧单客户端产物与历史测试数字不代表当前两包架构已通过验收。

## Docker 服务版启动（开发/服务器用途）

Docker/云部署是可选的开发和运维路径，以下旧启动器流程与桌面默认的独立 Client/Server 安装包分开使用。

前置条件：

1. 已安装并可启动 Docker Desktop。
2. 系统已安装 Microsoft Edge 或 Google Chrome。
3. 已在系统环境变量中设置 `DEEPSEEK_API_KEY`，或在 `dk-ai-agent/.env` 中填写有效 Key。
4. 首次启动前，在系统环境变量或 `dk-ai-agent/.env` 中设置 `ADMIN_INITIAL_PASSWORD`（至少 8 位，UTF-8 不超过 72 字节）；生产和开发环境均需配置。已有管理员时无需重新配置，也不会重置其密码。

旧 Docker 启动器入口：

```text
launcher/PsychCounselorLauncher.exe
```

启动器会先检查 `FRONTEND_PORT`（默认 `3001`）以及开发调试端口 `5432`、`8001`、`8123`，再检查 Docker、构建并启动 Compose 服务、等待健康检查，然后以独立应用窗口打开网页。基础 Compose 只向宿主机发布前端端口，所以只有 `FRONTEND_PORT` 冲突会阻止启动；三个调试端口被占用时只提示，不清理进程或容器。

启动器采用可缩放的深色工作台，左侧可切换“工作台”和“运行日志”。工作台显示真实启动阶段与活动提示，日志页提供实时输出和日志文件入口；启动失败后会保留原因摘要，停止服务未确认时也会明确提示。排查并修复后，关闭窗口并重新打开启动器即可再次启动。

当前端端口被占用时，弹窗会列出关联的 PID、进程名和 Docker 容器。选择“是”即确认安全清理占用项，选择“否”会自动从 `3002` 至 `3010` 选择空闲端口，选择“取消”则退出。关闭网页窗口或启动器后，启动器会执行 `docker compose down --remove-orphans`，停止本项目后台服务，但不会删除 PostgreSQL volume，因此历史会话仍会保留。启动失败的详细日志优先写入 `%LOCALAPPDATA%\DkPsychCounselorLauncher\launcher-last.log`；用户目录不可写时回退到 EXE 同目录。

若旧 Docker 启动器 EXE 尚未生成，在项目根目录运行：

```cmd
launcher\build-launcher.cmd
```

## Docker 服务版手动启动

### 1. 启动依赖

在 CMD 中执行：

```cmd
cd /d D:\dk-ai-agent\01_Personal_Projects\Psych_Counseling_Agent\dk-ai-agent
if not exist .env copy .env.example .env
set DEEPSEEK_API_KEY=sk-xxxx
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d postgres ai-worker
```

### 2. 启动 Java 后端

首次启动前，将下面的密码占位值替换为自行设置的强口令。直接运行 Java 时须在当前 CMD 设置环境变量，Compose 的 `.env` 不会自动导入该进程；已有管理员时可省略密码设置行。

```cmd
set JAVA_HOME=F:\.jdks\openjdk-25.0.1
set PATH=%JAVA_HOME%\bin;%PATH%
set AI_WORKER_BASE_URL=http://localhost:8001
set "ADMIN_INITIAL_PASSWORD=REPLACE_WITH_YOUR_STRONG_PASSWORD"
mvnw.cmd spring-boot:run
```

首次启动会下载本地 ONNX 模型（当前默认 `multilingual-e5-small`）；只有通过 manifest 准入的来源才会切分并写入 PgVector。快照版本包含模型版本、切块正文与来源元数据，已有快照须核对文档 ID、正文和元数据后才开放检索。当前 manifest 没有合格来源，应用可以启动，但检索集为空；专业、考试等必要知识域明确返回知识不足，不会借用旧库语料。

### 3. 启动 Vue 前端

另开一个 CMD：

```cmd
cd /d D:\dk-ai-agent\01_Personal_Projects\Psych_Counseling_Agent\dk-ai-agent\dk-ai-agent-frontend
npm.cmd ci
npm.cmd run dev
```

访问 `http://localhost:3001`，先注册或登录，再进入 `/psych-master`。首次启动会自动创建超管账号 `admin`：必须提前设置环境变量 `ADMIN_INITIAL_PASSWORD`，尚无管理员且该值为空或仅含空白时后端拒绝启动，不创建账号。日志不会输出口令或哈希，操作步骤见[技术设计 · 部署与启动](docs/TECHNICAL_DESIGN.md)。

主要接口：

- `POST /api/auth/register`、`POST /api/auth/login`、`POST /api/auth/logout`、`GET /api/auth/me`
- `POST /api/users/me/password`
- `GET /api/admin/users`、`/api/admin/stats` 等管理端点（仅 ADMIN）
- `POST /api/ai/counseling/chat/sync`
- `POST /api/ai/counseling/chat/sse`（正式前端链路；JSON body）
- `POST /api/ai/conversations`
- `GET /api/ai/conversations`
- `GET /api/exam/questions`、`POST /api/exam/questions/{id}/answer`（独立模拟练习，需登录；POST 需 CSRF token）
- `GET /api/health`

## 账号与数据隔离

- 注册/登录：`/login` 页面提供登录/注册双标签。注册成功即自动登录；用户名 3–32 位（字母、数字、下划线或中文），密码 8–72 字符。
- 会话：登录后由 HttpOnly + SameSite=Lax Cookie 维持，默认 24 小时有效（`SESSION_TIMEOUT` 可调）。账号被停用后其全部在线会话立即失效。
- 数据隔离：每个会话绑定创建者 `owner_id`，用户只能列出、读取和删除自己的会话；访问他人会话得到的结果与"会话不存在"完全相同，不泄露存在性。管理员删除用户时级联删除其会话、消息与记忆数据。
- 登录保护：同一用户名 15 分钟内失败 5 次锁定 15 分钟；锁定与密码错误返回同一泛化提示，不区分账号是否存在。进程内限流，不支持多副本共享。
- 超管：首次启动自动创建 `admin`。服务端必须设置 `ADMIN_INITIAL_PASSWORD`（至少 8 位，UTF-8 不超过 72 字节），尚无管理员且未配置有效口令时拒绝启动。口令和哈希均不写日志，请妥善保管并在首次登录后修改。已存在管理员时不重复创建，也不要求重新配置初始口令。
- 管理面板（ADMIN 可见，`/admin`）：统计卡片（用户总数/活跃/停用/会话总数/消息总数）；用户表（关键词搜索、状态筛选、分页、角色与状态徽标、最后登录时间）；行操作（停用并填原因、启用、一次性临时密码重置、删除并级联清理数据）；批量停用/启用；不能对自己执行停用或删除；可返回咨询页。

## 默认窗口

- 每个会话原文保留上限 1000 条消息：`CHAT_HISTORY_MAX_MESSAGES=1000`。触限前必先由 LLM 整合进长期记忆再滚动，信息不丢失。
- 长期记忆摘要不超过 1200 字：`CHAT_MEMORY_DIGEST_MAX_CHARS=1200`；未覆盖消息累计到 6 条即触发增量整合：`CHAT_MEMORY_FOLD_THRESHOLD=6`。
- 深度模式情景召回最多 4 个片段：`CHAT_MEMORY_RECALL_EPISODES=4`，每个片段不超过 300 字：`CHAT_MEMORY_RECALL_SNIPPET_CHARS=300`；关联假设最多 3 条：`DEEP_THINKING_ASSOCIATION_HYPOTHESES=3`。
- 快速模式不做情景召回，不为记忆新增任何模型或 Worker 调用。
- 普通回答读取最近 30 条消息：`CHAT_HISTORY_CONTEXT_WINDOW=30`。
- 深度规划读取最近 12 条消息：`DEEP_THINKING_HISTORY_MESSAGES=12`。
- 快速检索默认 `topK=4`、相似度阈值 `0.87`。
- 深度检索默认每条查询召回 6 个候选、阈值 `0.25`，最终保留最多 4 个证据；专业知识、政策依据与案例参考分开组织，当前所有证据均不做逐字稿搜索。

## 数据范围

运行时只加载 manifest 准入且限定路径中的案例材料（`psych-cases`）、专业知识（`psych-knowledge`）和政策材料（`psych-policy`）。仓库历史材料保存在 `dk-ai-agent/src/main/resources/document/`、`document/professional/` 和 `document/policy/`，这些文件仍保留在仓库和来源清单中，`quarantine`、`reference` 或审核 `pending` 的来源当前不进入运行时。没有获准的来源时，专业知识检索明确返回知识不足。以下数量仅为历史盘点：旧材料包括 810 个案例索引、799 份 raw 逐字稿（约 396 万字）、9 个案例分类 Markdown 和 1 个中性心理疏导框架；11 个案例缺少来源网站逐字稿，只能参考摘要。专业与政策目录中的种子摘要是项目整理文本，引用链接不表示外部机构授权或背书。

`psych-exams`、`psych-prompts` 是候选域，目前没有接入生产资源导入；考试意图虽有独立域路由，也不会因此获得可检索题库。实际 `exam/practice/seed.jsonl` 供 `/practice` 页面通过独立练习 API 使用：后端无状态、本地虚构模拟题，不调用模型、不保存答题记录；页面仅临时展示本轮结果，刷新、离开或重新开始后清除。不进入向量库、咨询检索或咨询记忆，也不属于正式题库或专业认证材料。

来源治理见 [KNOWLEDGE_GOVERNANCE](docs/KNOWLEDGE_GOVERNANCE.md)：`counseling-kb/manifests/sources.jsonl` 登记来源，`counseling-kb/validate_sources.py` 提供离线校验。旧 `raw/`、`cases.json`、`document/` 及其旧运行时文档仍登记为 `quarantine`，当前没有 approved production entry。manifest 已接入运行时准入：来源必须同时为 `production`、`review_status=approved`、`license_status=approved`、`pii_status=redacted/no_pii`，具备有效许可及人工审核记录，且 `runtime_path` 位于限定的应用内 Markdown 路径。当前没有合格来源，启动后检索集为空；专业、考试等必要知识域明确返回知识不足。旧 quarantine 语料不会进入检索；人工来源授权和隐私审查仍未完成，域名或 `Status: active` 也不能替代审核。

`COUNSELING_TRANSCRIPT_DIRECTORY` 是历史逐字稿检索配置，组件默认值为 `../counseling-kb/raw`。当前 manifest 不准入 raw，Java 服务路径不调用 `TranscriptSearchService`，向 Worker 发出的深度检索请求固定为 `snippetsPerCase=0`（关闭逐字稿读取）。腾讯云发布包不复制 raw，Compose 不设置逐字稿目录或挂载 raw，部署检查也不要求该目录；`verify-package.ps1` 明确拒绝含 raw 条目的 ZIP。原始逐字稿仍保留在仓库用于离线治理，设置历史配置不会启用当前服务路径的逐字稿检索。

## 安全边界

已实现：

- BCrypt 密码散列存储（strength 10），密码不落明文、不落日志。
- 登录限流与账号停用后的会话即时吊销；会话 Cookie 为 HttpOnly + SameSite=Lax。
- 会话/消息读写全链路按 `owner_id` 校验，跨用户访问与"不存在"不可区分。
- 全部 SQL 走 JdbcTemplate 参数化查询，不拼接用户输入。
- `/api/admin/**` 仅 ADMIN 可达，其余 `/api/**` 需登录；未认证返回 401 JSON，无权限返回 403 JSON。

仍存在的限制，如实保留：

- 咨询接口仅接受 POST body；旧 GET 聊天入口已移除。同步与流式请求共用安全、限流、所有权和持久化幂等链路。
- CSRF 已启用：先 GET `/api/auth/csrf`，所有状态变更请求带回 `X-CSRF-TOKEN`；登录后重新获取 token。前端及 eval 已接线。
- 本地示例允许注册和 HTTP；生产默认 Secure Cookie、关闭注册，服务器示例绑定环回地址并要求 HTTPS 反代。已有 `.env` 必须人工核对，升级不会覆盖它。限流、会话注册表和轮次恢复仍要求单副本。
- 尚无隐私数据自助删除（用户侧只能删除自己的会话）与完整内容审核。
- DeepSeek、向量检索和自动转录都可能出错，案例和时间戳需要人工核对。
- 出现自伤、自杀或正在发生的人身危险时，应立即联系当地急救、危机干预热线或正规医院，不能依赖本应用独立处置。
- API Key 仅由服务端持有：桌面 Server 用当前 Windows 用户 DPAPI 加密保存，Docker/云部署通过环境变量或本地 `.env` 配置；不要写入客户端配置、源码、文档、日志或截图。
- 桌面 Client 只保存连接偏好；本机 Server 提供停服加密冷备与隔离验证，远程服务的数据备份、恢复和保留策略由部署方负责。

## 修复与验证（2026-09-17）

该轮历史修复与验证明细见 [REPAIR_REPORT](docs/REPAIR_REPORT.md)，其中测试数字保留当时口径。检索后续测量见 [检索基线](eval/RAG_BASELINE.md)：2026-09-20 的 `multilingual-e5-small` 在 source-grounded seed 上 Document Hit@4 为 **0.72**；**0.04** 是 2026-09-17 MiniLM 的历史 Document Hit@4，旧称 Recall 不准确。这个种子集尚非独立人工 golden set，0.72 不是人工 golden Recall 或真实用户准确率，工程测试通过也不代表对话或检索质量合格。

## 文档

- [功能说明](docs/FUNCTIONAL_SPEC.md)——产品功能与使用：账号体系、咨询对话、长期记忆、管理后台、页面与路由清单
- [技术设计](docs/TECHNICAL_DESIGN.md)——架构、认证与数据隔离、分层记忆、RAG、API 合约、部署与启动、测试与 E2E 复现
- [知识来源治理](docs/KNOWLEDGE_GOVERNANCE.md)——来源登记、授权与隐私审核、隔离状态及离线校验边界
- [服务器旧版本增量升级](docs/SERVER_INCREMENTAL_UPGRADE.md)——本轮审计修复的变更清单、备份换版、验收与回滚

项目统一使用 UTF-8。编辑器、Maven、Python、容器和生成文件的编码约束见 `.editorconfig` 与 `.gitattributes`。
