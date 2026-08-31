# AI 心理咨询师

这是一个面向日常心理疏导的本地 RAG 应用。它先理解用户的感受并澄清事实，在信息不足时以简短提问为主；画像足够清晰后，会先复述确认并征得许可，再进行完整梳理。

当前知识库整理自公开连麦案例。案例只用于检索参考和原文溯源，不定义 AI 的身份、立场或表达风格。项目不模仿、代表或绑定任何现实人物。

> 本项目仅供个人学习和本地体验，不是医疗服务，不能替代持证心理咨询师、精神科医生或紧急救援服务。账号体系为本地单实例设计（登录限流与会话注册表均为进程内状态，不支持多副本共享），开放注册仅适合本机使用，不应直接部署到公网。

## 功能

- 单一产品入口：AI 心理咨询师。
- 友好简短的首次问候，不在用户开口前主动分析。
- 快速回复：Java `QuestionAnswerAdvisor` + PgVector 两级 RAG。
- 深度思考：Java 25 并发主控 + 可选 Python Worker，执行查询规划、中文 BM25/RRF 重排、证据筛选和逐字稿核验。
- 自动回退：Python Worker 不可用时回退 Java Agent，再失败则回到基础 Java RAG。
- SSE 流式输出：展示真实的规划、检索、核对、组织回答和回退阶段，不展示隐藏思维链。
- 账号体系：注册/登录、24 小时会话、按用户隔离的会话与记忆、管理员后台（见"账号与数据隔离"）。
- 会话持久化：PostgreSQL 保存历史会话，页面支持新建、切换和删除。
- 安全 Markdown：AI 回复经过 Markdown 渲染和 HTML 消毒，用户输入按纯文本显示。
- 二级溯源：摘要命中后按案例编号读取 raw 逐字稿，返回带时间戳的参考片段。

## 技术结构

```text
Psych_Counseling_Agent/
├── dk-ai-agent/                  Java 25 后端、Vue 前端和 Docker Compose
├── ai-worker/                    Python 3.12 深度检索 Worker
├── counseling-kb/                    案例索引、分类文档和 raw 逐字稿
├── docs/                         功能与技术文档
└── launcher/                     Windows 一键启动器源码、构建脚本和 EXE
```

主要组件：

- Java 25、Spring Boot 3.5.16、Spring AI 1.0.9。
- Python 3.12、FastAPI、Pydantic、jieba、BM25/RRF。
- Vue 3、Vite、SSE、markdown-it、DOMPurify。
- PostgreSQL 16 + pgvector、本地 ONNX embedding。
- DeepSeek V4 Flash 文本生成与网页搜索。

## 一键启动

前置条件：

1. 已安装并可启动 Docker Desktop。
2. 系统已安装 Microsoft Edge 或 Google Chrome。
3. 已在系统环境变量中设置 `DEEPSEEK_API_KEY`，或在 `dk-ai-agent/.env` 中填写有效 Key。

双击：

```text
launcher/PsychCounselorLauncher.exe
```

启动器会先检查 `FRONTEND_PORT`（默认 `3001`）以及开发调试端口 `5432`、`8001`、`8123`，再检查 Docker、构建并启动 Compose 服务、等待健康检查，然后以独立应用窗口打开网页。基础 Compose 只向宿主机发布前端端口，所以只有 `FRONTEND_PORT` 冲突会阻止启动；三个调试端口被占用时只提示，不清理进程或容器。

当前端端口被占用时，弹窗会列出关联的 PID、进程名和 Docker 容器。选择“是”即确认安全清理占用项，选择“否”会自动从 `3002` 至 `3010` 选择空闲端口，选择“取消”则退出。关闭网页窗口或启动器后，启动器会执行 `docker compose down --remove-orphans`，停止本项目后台服务，但不会删除 PostgreSQL volume，因此历史会话仍会保留。启动失败的详细日志优先写入 `%LOCALAPPDATA%\DkPsychCounselorLauncher\launcher-last.log`；用户目录不可写时回退到 EXE 同目录。

若 EXE 尚未生成，在项目根目录运行：

```cmd
launcher\build-launcher.cmd
```

## 手动启动

### 1. 启动依赖

在 CMD 中执行：

```cmd
cd /d D:\dk-ai-agent\01_Personal_Projects\Psych_Counseling_Agent\dk-ai-agent
if not exist .env copy .env.example .env
set DEEPSEEK_API_KEY=sk-xxxx
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d postgres ai-worker
```

### 2. 启动 Java 后端

```cmd
set JAVA_HOME=F:\.jdks\openjdk-25.0.1
set PATH=%JAVA_HOME%\bin;%PATH%
set AI_WORKER_BASE_URL=http://localhost:8001
mvnw.cmd spring-boot:run
```

首次启动会下载本地 ONNX 模型并把知识库 Markdown 切分后写入 PgVector，耗时会明显高于后续启动。知识库内容不变时会按版本哈希跳过重复灌库。

### 3. 启动 Vue 前端

另开一个 CMD：

```cmd
cd /d D:\dk-ai-agent\01_Personal_Projects\Psych_Counseling_Agent\dk-ai-agent\dk-ai-agent-frontend
npm.cmd ci
npm.cmd run dev
```

访问 `http://localhost:3001`，先注册或登录，再进入 `/psych-master`。首次启动会自动创建超管账号 `admin`：初始口令取环境变量 `ADMIN_INITIAL_PASSWORD`，未设置时随机生成并在后端日志中以 WARN 级别输出一次，随后不再显示，操作步骤见[技术设计 · 部署与启动](docs/TECHNICAL_DESIGN.md)。

主要接口：

- `POST /api/auth/register`、`POST /api/auth/login`、`POST /api/auth/logout`、`GET /api/auth/me`
- `POST /api/users/me/password`
- `GET /api/admin/users`、`/api/admin/stats` 等管理端点（仅 ADMIN）
- `GET /api/ai/counseling/chat/sync`
- `POST /api/ai/counseling/chat/sse`（正式前端链路；JSON body）
- `POST /api/ai/conversations`
- `GET /api/ai/conversations`
- `GET /api/health`

## 账号与数据隔离

- 注册/登录：`/login` 页面提供登录/注册双标签。注册成功即自动登录；用户名 3–32 位（字母、数字、下划线或中文），密码 8–72 字符。
- 会话：登录后由 HttpOnly + SameSite=Lax Cookie 维持，默认 24 小时有效（`SESSION_TIMEOUT` 可调）。账号被停用后其全部在线会话立即失效。
- 数据隔离：每个会话绑定创建者 `owner_id`，用户只能列出、读取和删除自己的会话；访问他人会话得到的结果与"会话不存在"完全相同，不泄露存在性。管理员删除用户时级联删除其会话、消息与记忆数据。
- 登录保护：同一用户名 15 分钟内失败 5 次锁定 15 分钟；锁定与密码错误返回同一泛化提示，不区分账号是否存在。进程内限流，不支持多副本共享。
- 超管：首次启动自动创建 `admin`。初始口令取 `ADMIN_INITIAL_PASSWORD`；未设置则随机生成 12 位口令，仅在后端日志 WARN 行显示一次，请立即记录并修改。已存在管理员时不重复创建。
- 管理面板（ADMIN 可见，`/admin`）：统计卡片（用户总数/活跃/停用/会话总数/消息总数）；用户表（关键词搜索、状态筛选、分页、角色与状态徽标、最后登录时间）；行操作（停用并填原因、启用、一次性临时密码重置、删除并级联清理数据）；批量停用/启用；不能对自己执行停用或删除；可返回咨询页。

## 默认窗口

- 每个会话原文保留上限 1000 条消息：`CHAT_HISTORY_MAX_MESSAGES=1000`。触限前必先由 LLM 整合进长期记忆再滚动，信息不丢失。
- 长期记忆摘要不超过 1200 字：`CHAT_MEMORY_DIGEST_MAX_CHARS=1200`；未覆盖消息累计到 6 条即触发增量整合：`CHAT_MEMORY_FOLD_THRESHOLD=6`。
- 深度模式情景召回最多 4 个片段：`CHAT_MEMORY_RECALL_EPISODES=4`，每个片段不超过 300 字：`CHAT_MEMORY_RECALL_SNIPPET_CHARS=300`；关联假设最多 3 条：`DEEP_THINKING_ASSOCIATION_HYPOTHESES=3`。
- 快速模式不做情景召回，不为记忆新增任何模型或 Worker 调用。
- 普通回答读取最近 30 条消息：`CHAT_HISTORY_CONTEXT_WINDOW=30`。
- 深度规划读取最近 12 条消息：`DEEP_THINKING_HISTORY_MESSAGES=12`。
- 快速检索默认 `topK=4`、相似度阈值 `0.3`。
- 深度检索默认每条查询召回 6 个候选、阈值 `0.25`，最终保留最多 4 个案例证据。

## 数据范围

- 810 个案例索引。
- 799 份 raw 逐字稿，约 396 万字。
- 11 份来源网站缺失的逐字稿只能使用摘要层信息。
- 9 个案例分类 Markdown 和 1 个中性心理疏导框架。

逐字稿默认从 `../counseling-kb/raw` 读取。若从其他工作目录启动，可设置：

```cmd
set COUNSELING_TRANSCRIPT_DIRECTORY=D:\absolute\path\to\counseling-kb\raw
```

## 安全边界

已实现：

- BCrypt 密码散列存储（strength 10），密码不落明文、不落日志。
- 登录限流与账号停用后的会话即时吊销；会话 Cookie 为 HttpOnly + SameSite=Lax。
- 会话/消息读写全链路按 `owner_id` 校验，跨用户访问与"不存在"不可区分。
- 全部 SQL 走 JdbcTemplate 参数化查询，不拼接用户输入。
- `/api/admin/**` 仅 ADMIN 可达，其余 `/api/**` 需登录；未认证返回 401 JSON，无权限返回 403 JSON。

仍存在的限制，如实保留：

- 正式前端已通过 POST body 发送心理消息；为兼容旧客户端仍保留 GET 流式入口，旧入口会把消息暴露在浏览器历史或代理日志中，不应继续使用。
- CSRF 防护仍关闭，当前依赖 SameSite=Lax Cookie 与默认本地绑定缓解，不构成对公网部署的完整防护。
- 注册默认开放且无邀请码，仅适合本地部署；进程内限流与会话注册表不支持多副本，暴露公网前必须收敛注册入口并补齐审计。
- 尚无隐私数据自助删除（用户侧只能删除自己的会话）与完整内容审核。
- DeepSeek、向量检索和自动转录都可能出错，案例和时间戳需要人工核对。
- 出现自伤、自杀或正在发生的人身危险时，应立即联系当地急救、危机干预热线或正规医院，不能依赖本应用独立处置。
- API Key 只能放在环境变量或本地 `.env`，不要写入源码、文档、日志或截图。

## 文档

- [功能说明](docs/FUNCTIONAL_SPEC.md)——产品功能与使用：账号体系、咨询对话、长期记忆、管理后台、页面与路由清单
- [技术设计](docs/TECHNICAL_DESIGN.md)——架构、认证与数据隔离、分层记忆、RAG、API 合约、部署与启动、测试与 E2E 复现
- [服务器旧版本增量升级](docs/SERVER_INCREMENTAL_UPGRADE.md)——本轮审计修复的变更清单、备份换版、验收与回滚

项目统一使用 UTF-8。编辑器、Maven、Python、容器和生成文件的编码约束见 `.editorconfig` 与 `.gitattributes`。
