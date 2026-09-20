# 服务器旧版本增量升级指南

本文用于把本仓库当前这批审计修复同步到服务器上的旧版“AI 心理咨询师”项目。它不是首次部署说明；首次部署、HTTPS、安全组和日常运维仍以 [`deploy/tencent-cloud/DEPLOY_TENCENT_CLOUD.md`](../deploy/tencent-cloud/DEPLOY_TENCENT_CLOUD.md) 为准。

> 推荐使用第 3 节的完整部署包换版，不要在生产服务器上逐个复制 Java/Python 文件。完整换版能同时更新后端、Worker、前端和部署脚本，并保留服务器 `.env`、PostgreSQL 数据卷和模型缓存卷。

## 1. 本次更新内容

### 1.1 修复错误的长期记忆召回

Python Worker 的 RRF 以及 Worker 不可用时的 Java heuristic fallback，现在都只保留存在真实关键词命中的历史消息。

更新前，零词法命中的较新消息可能因为新近度加分被补进召回结果，导致无关历史被注入模型上下文。更新后：

- 关键词重叠决定候选是否相关；
- 新近度只能重新排序真实命中；
- 新近度不能凭空制造相关性。

生产代码涉及：

```text
ai-worker/src/dk_ai_worker/service.py
dk-ai-agent/src/main/java/com/dk/dkaiagent/memory/ConversationMemoryService.java
```

### 1.2 删除不可靠的聊天回答缓存

已删除：

```text
dk-ai-agent/src/main/java/com/dk/dkaiagent/cache/AnswerCache.java
```

同时移除控制器缓存逻辑以及 `application.yml` 中的回答缓存配置。旧实现按消息文本和历史数量推断重试，既无法稳定实现请求幂等，也可能把用户连续发送的相同文本误判成同一轮对话。

升级后，相同文本会作为两个独立会话轮次处理。将来如需网络请求幂等，应由客户端发送唯一 `requestId`，不能恢复按文本缓存。

旧服务器 `.env` 如果存在以下变量，可以删除：

```dotenv
CHAT_ANSWER_CACHE_TTL_SECONDS=...
CHAT_ANSWER_CACHE_MAX_ENTRIES=...
```

遗留变量不会被新版本读取，不删除也不会阻止启动。

### 1.3 禁止回答落库失败后的“虚假成功”

更新前，助手回答写 PostgreSQL 失败时，异常可能被吞掉，SSE 仍发送完成信号。用户会看到回答成功，但刷新后回答消失。

更新后：

- 普通数据库或持久化故障向上传播，SSE 以失败结束，不发送虚假的成功完成信号；
- 仅当会话在生成期间被删除或已不可用时跳过归档，避免复活已删除会话或发生越权写入；
- 回答原文落库后的长期记忆整合仍是可降级增强，摘要触发失败不撤销已经保存的原文。

生产代码涉及：

```text
dk-ai-agent/src/main/java/com/dk/dkaiagent/app/CounselingApp.java
dk-ai-agent/src/main/java/com/dk/dkaiagent/history/ConversationUnavailableException.java
```

### 1.4 修正异常到 HTTP 状态码的映射

更新前，控制器可能把所有 `IllegalStateException` 都返回为 404，导致数据库、模型或程序故障被伪装成“会话不存在”。

更新后只有专用的 `ConversationUnavailableException` 返回 404；其他内部状态故障保留为服务器错误，便于监控和排查。

生产代码涉及：

```text
dk-ai-agent/src/main/java/com/dk/dkaiagent/controller/AiController.java
dk-ai-agent/src/main/java/com/dk/dkaiagent/history/ConversationHistoryService.java
dk-ai-agent/src/main/java/com/dk/dkaiagent/history/ConversationUnavailableException.java
```

### 1.5 文档与接口说明纠正

- 正式前端聊天链路是 `POST /api/ai/counseling/chat/sse`，咨询正文放在 JSON body；
- GET 聊天兼容入口已连同 `server_sent_event`、`sse_emitter` 两个旧端点一并删除（咨询正文进 URL 会留档于访问日志/浏览器历史）；若线上仍有旧客户端直连 GET，会收到 405，需先升级前端再发布本包；
- 删除三个重复 README；
- 保留 RAG 知识库 Markdown 和 `ai-worker/README.md`，它们分别是运行数据与 Python 包元数据依赖。

## 2. 数据库和配置兼容性

### 2.1 本次没有数据库结构迁移

本批修复没有新增或删除 PostgreSQL 表、列、索引，也不需要手工执行 SQL。已有以下数据应原样保留：

- 用户和管理员账号；
- 会话与消息；
- 长期记忆摘要；
- PgVector 向量数据。

虽然没有 schema migration，升级前仍必须备份数据库，因为容器构建、目录切换或人为操作仍可能失败。

### 2.2 不要用新模板覆盖生产 `.env`

升级时必须沿用服务器当前：

```text
/opt/psych-counseling-agent/dk-ai-agent/.env
```

尤其不要改变：

```dotenv
COMPOSE_PROJECT_NAME=psych-counseling-agent
POSTGRES_DB=...
POSTGRES_USER=...
POSTGRES_PASSWORD=...
AI_WORKER_SHARED_SECRET=...
DEEPSEEK_API_KEY=...
SESSION_COOKIE_SECURE=...
```

`COMPOSE_PROJECT_NAME` 一旦改变，Compose 可能创建另一组空数据卷，看起来会像“用户和会话全部丢失”。PostgreSQL 命名卷初始化后，也不能只修改 `.env` 密码来改变数据库内密码。

### 2.3 不要删除 Docker volumes

禁止执行：

```bash
docker compose down -v
docker volume rm psych-counseling-agent_postgres_data
```

正常 `./manage.sh stop` 只停止容器，不删除数据卷。

## 3. 推荐升级：完整部署包换版

### 3.1 在开发机确认并构建

先确认本次需要上线的代码已经审核完成。当前工作区若仍有未提交修改，部署包会包含构建脚本复制到 stage 的实际文件内容，因此应先检查：

```powershell
git status --short
git diff --check
```

运行自动验证：

```powershell
cd dk-ai-agent
.\mvnw.cmd test
cd ..\ai-worker
.\.venv\Scripts\python.exe -m pytest
cd ..\dk-ai-agent\dk-ai-agent-frontend
npm.cmd ci
npm.cmd run build
cd ..\..
```

本次审计完成时的基线结果为：

```text
Java:   244 tests，0 failures，0 errors，6 skipped
Python: 22 passed
Frontend: Vite production build succeeded
```

跳过的 Java 项目是需要真实模型、数据库或显式环境变量的门控集成测试。

然后在项目根目录生成腾讯云部署包：

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy\tencent-cloud\build-package.ps1
```

产物位于：

```text
release/Psych_Counseling_Agent_TencentCloud_3004_YYYYMMDD-HHMMSS.zip
release/Psych_Counseling_Agent_TencentCloud_3004_YYYYMMDD-HHMMSS.zip.sha256
```

部署包不应包含真实 `.env`、API Key、`target`、`node_modules`、`.venv` 或测试缓存。构建脚本会检查疑似 API Key，并生成 `PACKAGE_MANIFEST.json`。

### 3.2 上传并校验

把 ZIP 和同名 `.sha256` 上传到服务器同一目录，然后执行：

```bash
cd ~
PACKAGE='Psych_Counseling_Agent_TencentCloud_3004_YYYYMMDD-HHMMSS.zip'
test -f "$PACKAGE" && test -f "$PACKAGE.sha256"
sha256sum -c "$PACKAGE.sha256"
```

必须看到 `OK`。不要用 `*.zip` 自动选包，以免服务器存在多个版本时误选旧文件。

### 3.3 备份并安全切换目录

下面流程会：

1. 解压到新的 release 目录；
2. 把旧版生产 `.env` 复制到新版；
3. 在旧服务仍在线时预检查新版；
4. 备份 PostgreSQL；
5. 停旧服务并原子式切换目录；
6. 重新构建并启动全部容器。

```bash
(
set -Eeuo pipefail
cd ~
PACKAGE='Psych_Counseling_Agent_TencentCloud_3004_YYYYMMDD-HHMMSS.zip'
test -f "$PACKAGE" && test -f "$PACKAGE.sha256"
sha256sum -c "$PACKAGE.sha256"

CURRENT_DIR='/opt/psych-counseling-agent'
test -f "$CURRENT_DIR/dk-ai-agent/.env"
test -x "$CURRENT_DIR/manage.sh"

RELEASE_DIR="/opt/psych-release-$(date +%Y%m%d-%H%M%S)"
sudo mkdir -p "$RELEASE_DIR"
sudo unzip -q "$PACKAGE" -d "$RELEASE_DIR"
sudo chown -R "$USER":"$USER" "$RELEASE_DIR"

NEW_DIR="$RELEASE_DIR/Psych_Counseling_Agent_Server"
test -f "$NEW_DIR/manage.sh"
test -f "$NEW_DIR/dk-ai-agent/docker-compose.yml"
test -d "$NEW_DIR/counseling-kb/raw"

cp "$CURRENT_DIR/dk-ai-agent/.env" "$NEW_DIR/dk-ai-agent/.env"
chmod 600 "$NEW_DIR/dk-ai-agent/.env"
chmod +x "$NEW_DIR/manage.sh"
bash -n "$NEW_DIR/manage.sh"

# 旧服务保持在线，先验证新包、生产配置、语料和 Compose。
cd "$NEW_DIR"
./manage.sh check

# 备份必须成功后才停机。
cd "$CURRENT_DIR"
./manage.sh backup
./manage.sh stop

PREVIOUS_DIR="/opt/psych-counseling-agent.previous-$(date +%Y%m%d-%H%M%S)"
echo "旧版本保留在：$PREVIOUS_DIR"
sudo mv "$CURRENT_DIR" "$PREVIOUS_DIR"
sudo mv "$NEW_DIR" "$CURRENT_DIR"
sudo chown -R "$USER":"$USER" "$CURRENT_DIR"

cd "$CURRENT_DIR"
./manage.sh check
./manage.sh deploy
)
```

`deploy` 使用 `--build`，会重建 Java 后端、Python Worker 和 Vue 前端镜像。不能只重启旧容器，否则新源码不会进入镜像。

## 4. 仅在无法整包换版时手工同步

手工增量方式更容易遗漏删除文件或前端资源，只作为应急方案。至少要同步以下生产文件：

```text
ai-worker/src/dk_ai_worker/service.py
dk-ai-agent/src/main/java/com/dk/dkaiagent/app/CounselingApp.java
dk-ai-agent/src/main/java/com/dk/dkaiagent/controller/AiController.java
dk-ai-agent/src/main/java/com/dk/dkaiagent/history/ConversationHistoryService.java
dk-ai-agent/src/main/java/com/dk/dkaiagent/history/ConversationUnavailableException.java
dk-ai-agent/src/main/java/com/dk/dkaiagent/memory/ConversationMemoryService.java
dk-ai-agent/src/main/resources/application.yml
```

并在服务器源码中删除：

```text
dk-ai-agent/src/main/java/com/dk/dkaiagent/cache/AnswerCache.java
```

如果旧服务器源码与当前版本相差较大，不要直接覆盖这些单文件：类签名、依赖和数据库实现可能已经不同。应改用完整部署包，或先在独立分支进行三方合并和测试。

同步完成后必须重新构建，而不是简单 `restart`：

```bash
cd /opt/psych-counseling-agent
./manage.sh backup
./manage.sh deploy
```

如果服务器的旧项目不是本仓库当前 Docker Compose 结构，应按其原部署方式分别重建 Java JAR、Worker 镜像和前端静态资源；不要直接照搬 `manage.sh`。

## 5. 升级后验收

### 5.1 容器与健康检查

```bash
cd /opt/psych-counseling-agent
./manage.sh status
curl -i http://127.0.0.1:3004/api/health
./manage.sh logs backend
./manage.sh logs ai-worker
```

确认：

- `postgres`、`backend`、`ai-worker`、`frontend` 均正常；
- 健康接口返回 2xx；
- 后端没有持续数据库连接错误；
- Worker 没有 shared secret 不一致或持续 401；
- 访问 HTTPS 部署时登录 Cookie 能正常保留。

### 5.2 数据保留检查

使用已有普通账号和管理员账号验证：

1. 原账号仍可登录；
2. 原会话列表和历史消息仍存在；
3. 管理后台用户、会话和消息统计没有异常归零；
4. 新建会话、发送消息、刷新页面后，助手回答仍存在；
5. 删除会话后不会因在途回答重新出现。

如果所有数据突然为空，先停止写入并检查 `COMPOSE_PROJECT_NAME` 和实际挂载卷，不要初始化新数据或删除旧卷：

```bash
cd /opt/psych-counseling-agent
docker compose --env-file dk-ai-agent/.env -f dk-ai-agent/docker-compose.yml config --volumes
docker volume ls | grep psych-counseling-agent
```

### 5.3 功能回归

至少完成以下用例：

- 快速回复可以正常流式输出并保存；
- 深度思考可以调用 Worker，Worker 不可用时 Java fallback 仍可回答；
- 连续发送两次完全相同的文本，会产生两个独立轮次，而不是回放缓存；
- 人为停止 PostgreSQL 后发起测试请求时，客户端不能收到“成功完成”后刷新丢回答；测试结束后立即恢复数据库；
- 使用与当前问题无关的历史消息测试长期召回，确认不会仅因消息较新而被召回。

生产环境不要直接破坏数据库做故障演练。持久化失败测试应在预发布环境或维护窗口进行。

### 5.4 日志观察

升级后至少观察一个业务周期：

```bash
./manage.sh logs backend
./manage.sh logs ai-worker
```

重点关注：

- assistant message archive/persistence failures；
- AI Worker contract、401、timeout 或 circuit breaker；
- PostgreSQL connection timeout；
- memory consolidation retry；
- SSE 请求异常结束率。

## 6. 回滚

如果新版本启动或验收失败，使用切换时输出的旧目录完整路径：

```bash
(
set -Eeuo pipefail
PREVIOUS_DIR='/opt/psych-counseling-agent.previous-YYYYMMDD-HHMMSS'
CURRENT_DIR='/opt/psych-counseling-agent'
FAILED_DIR="/opt/psych-counseling-agent.failed-$(date +%Y%m%d-%H%M%S)"

test -d "$PREVIOUS_DIR"
(cd "$CURRENT_DIR" && ./manage.sh stop) || true
sudo mv "$CURRENT_DIR" "$FAILED_DIR"
sudo mv "$PREVIOUS_DIR" "$CURRENT_DIR"
sudo chown -R "$USER":"$USER" "$CURRENT_DIR"
cd "$CURRENT_DIR"
./manage.sh deploy
)
```

本次没有数据库 schema 迁移，通常可以直接回滚应用目录并复用原数据卷。如果升级后已经产生新消息，回滚应用不会删除这些数据。只有确认数据库本身损坏或被误操作时，才考虑从 dump 恢复；恢复会覆盖备份时间点之后的数据，必须另行评估。

新版本稳定后再手工清理：

- `/opt/psych-counseling-agent.previous-*` 旧应用目录；
- `/opt/psych-counseling-agent.failed-*` 失败目录；
- 不再需要的旧部署 ZIP。

不要清理 `psych-counseling-agent_*` Docker volumes。数据库备份还应复制到腾讯云 COS 或另一台机器，不能只保留在同一块云硬盘。

## 7. 本次变更边界

本指南只覆盖本轮审计修复。它不表示以下生产安全事项已经解决：

- CSRF 已在本轮启用：必须同时更新后端、前端及自动调用客户端；
- 生产注册默认关闭；已有环境变量可覆盖默认值，需核对；
- 进程内限流与会话注册表不支持多副本共享；
- 真实咨询数据仍必须使用 HTTPS，不能长期通过公网 HTTP 提供服务。

若旧服务器已经针对域名、Nginx、证书、备份、监控或 `.env` 做过私有修改，升级时保留这些生产配置，不要用仓库示例文件直接覆盖。
## 8. 第二轮增量（2026-08-29：幂等重发与链路收口）

### 8.1 用户消息幂等归档（clientMsgId）

- 前端为每轮发送生成 UUID（`PsychMaster.vue` 的 `newClientMsgId`），随 POST body 以 `clientMsgId` 传入；SSE 中断后自动/手动重发沿用同一键。
- `psych_chat_message` 新增列 `client_msg_id VARCHAR(64)` 与唯一索引 `(conversation_id, client_msg_id)`，由 `initializeSchema` 的 `ADD COLUMN IF NOT EXISTS` / `CREATE UNIQUE INDEX IF NOT EXISTS` 幂等追加，**无需手工 SQL**；用户消息 INSERT 带 `ON CONFLICT (conversation_id, client_msg_id) DO NOTHING`，重放原子地不落行。
- **注意**：这修正了 §2.1 "本次没有数据库结构迁移"的说法——第二轮起表结构有一次列与索引的幂等追加，仍不需要手工执行 SQL，重启 backend 即自动生效。

### 8.2 前端分级容错

- 首字节前失败（一个 delta 都没收到）自动用同一 clientMsgId 重发一次，两种模式都显示"网络波动，正在重新连接…"指示条。
- 流中途断开不自动重发（避免与已显示的半截回答叠加），输入区上方出现"重新发送"按钮，点击后沿用原 aiMessageIndex 与 clientMsgId 重开流。

### 8.3 聊天链路收口（CounselingTurnPipeline）

- 新增 `agent/counseling/CounselingTurnPipeline`：归档每轮恰好一次、按 `deepThinking` 分流、事件统一映射为 `CounselingStreamEvent`；`AiController` 不再感知两种模式。
- `CounselingAgentExecutor` 接口由 `stream(...)` 收窄为 `prepareAndAnswer(...)`（不再负责归档）；`CounselingApp.doChatWithRagByStream` 已删除，SSE 链路一律使用 `*Prepared` 变体。SSE 事件序列与 phase 名不变，前端除重发逻辑外零改动。

### 8.4 System Prompt 精简

- `CounselingApp.SYSTEM_PROMPT` 由约 2600 字符压缩至约 2100（-18%），语义零删减：三层信息、阶段许可门槛、字数预算、工具规则、危机干预协议全部保留。DeepSeek 对稳定前缀自动上下文缓存，成本差异可忽略，收益以可读性与注意力聚焦为主。

### 8.5 前端资源缓存与视口

- `index.html` viewport meta 追加 `viewport-fit=cover, interactive-widget=resizes-content`（刘海屏 safe-area 与 Android Chrome 键盘模式）。
- `nginx.conf` 对 `/index.html` 追加 `Cache-Control: no-cache`：静态资源本就带内容哈希（immutable），入口 HTML 缓存住会导致发布后手机仍加载旧版 JS/CSS。

## 9. 第三轮增量（2026-08-30：碎片化对话节奏）

本轮改的是"AI 怎么说话"，不涉及接口契约与数据库结构（`client_msg_id` 迁移已在第二轮完成）。

- **提问节奏限速器**（`app/RhythmDirectives`，确定性规则、零 LLM 调用）：最近 3 轮中助手连续 2 轮以问句收尾 → 下一轮强制纯反映；用户回复 ≤3 code point 且上一条助手消息带问句 → 触发回避退让。指令经 `systemPromptWithDigest` 统一注入快速/深度/降级三条链路。
- **System Prompt 重写**：加入"AI 是提问者与陪伴者"的位置互换声明；阶段一改为"承接后从回应工具箱（反映/肯定/只陪伴/提问）选动作，默认反映"；新增回避退让协议、提问形态规范（贴最后一句、慎用"为什么"）与宣泄轮预算（20–80 字零提问）。三阶段许可门槛与危机协议原样保留且优先级最高。
- **worker 契约增量**：`PlanResponse` 新增 `response_mode ∈ {listen,clarify,explore}`（带默认值，向后兼容）与 `next_probe ≤120`（选题方向而非问题原文）；启发式降级路径同步（同意梳理→explore、极短或强情绪→listen）。
- **深度链路**：`buildContext` 头部注入"本轮回应策略"块（内部指令、截断免疫）。
- **评测**：新增 `docs/EVAL_DIALOG_CASES.md`（16 个 golden 对话案例 + 人工评测协议）。
- **升级注意**：本轮必须同时重建 `backend` 与 `ai-worker` 两个镜像（只换 backend 会因 shared secret 不匹配而 401；只换 worker 则新字段不被消费）。`.env` 需提供 `DEEPSEEK_API_KEY` 与 `AI_WORKER_SHARED_SECRET`。

## 10. 第四轮增量（2026-09-02：风险分级 + 记忆回访 + 自动评测）

- **四级风险分层**：`RiskTier`（NONE/DISTRESS/PASSIVE/IMMINENT），`SafetyTerms.assess` 确定性判定。
  IMMINENT（手段/计划/进行中）由 `CounselingTurnPipeline` 统一拦截——**快速模式由此补上了
  此前完全缺失的危机前置检查**——返回 `CrisisResponse` 专用模板（不经 LLM，资源表
  `app.safety.hotlines` 可配置）。PASSIVE 走普通链 + `SafetyDirectives` 安全姿态注入。
- **输出侧检查**：`SafetyOutputGuard` 在 PASSIVE/IMMINENT 轮校验禁忌应和与资源缺失，流尾自动补求助资源。
- **记忆回访**：`MemoryFollowUp` 在"隔 ≥6 小时回来 + 摘要有待确认事项"时注入回访指令（零延迟）；
  快速模式回访轮额外做一次情景召回（原话级回访）。
- **自动评测**：`eval/run_eval.py + cases.yaml`，对运行栈跑 7 个对话用例，确定性断言 + LLM-as-judge，
  报告写 `eval/report.md`（需评测账号密码与 DEEPSEEK_API_KEY）。
- **升级注意**：本轮需重建 `backend` 镜像；无数据库迁移；`app.safety.*` 有默认值，无需改 `.env`。

## 11. 第五轮增量（2026-09-02：真洞修复 + 成本护栏 + 持续关注）

- **流式中断不丢回答**：两个流式方法的归档从 `doOnComplete` 改为 `doFinally`——用户关标签页/
  切会话/点停止时，已生成的部分内容照常归档（CANCEL 与 COMPLETE 均落库；ERROR 保守跳过）。
  前端新增"停止生成"按钮（生成中替换发送按钮）。
- **危机事件审计**：IMMINENT=WARN / PASSIVE=INFO 结构化日志（pipeline 与 SafetyDirectives），
  只记分级/会话/主体，正文绝不入日志。
- **词表单一事实源**：worker `_DISTRESS_MARKERS` 与 Java `SafetyTerms.DISTRESS_TERMS` 对齐
  （移除"活不下去"、补"熬不住"），worker 一致性测试锁定；语义差异见两侧注释。
- **成本护栏**：chat `max-tokens: 2000`；新增 `ChatRateLimitService`（单用户 60s/12 条，429 拒绝），
  前端对 429 显示专属文案且不自动重发。
- **持续关注**：`SafetyDirectives` 扫描最近 user 消息取最高分级——历史有消极意念而本轮缓和时，
  注入持续关注指令（确认状态、不当过去、不重提施压）。
- **prompt few-shot**：SYSTEM_PROMPT 增三组"好/不要这样"对照示例（宣泄反映、短答回避退让、求建议先澄清）。
- **前端**：导出当前会话为 .md（纯前端）；`connectSSE` 错误携带 HTTP 状态码。
- **评估沉淀**：新增 `docs/IMPROVEMENT_BACKLOG.md`（安全加固包/可观测性/RAG 升级/数据治理/单副本检查单）。
- **升级注意**：重建 backend 镜像；`app.chat-rate-limit.*` 有默认值，无需改 `.env`。

## 2026-09-17 修复版升级补充

前面按日期记录的 doFinally、7 用例等内容是历史实现，不再描述当前版本。当前实现和测试证据以 `REPAIR_REPORT.md` 为准。

1. 备份数据库与环境配置，确认单副本；停旧实例后再起新实例，不能滚动重叠启动。
2. 用 `mvnw clean package` 构建，避免旧名称知识库资源残留在 target/classes。前端需要 Node 24.15+（本轮 24.19 验证），执行 npm ci、lint、test、build。
3. 首次启动自动新建 psych_chat_turn 和索引，不改写现有正文。回滚旧代码前先停止新实例；旧版本忽略新表，但不再具备新的幂等及安全保障。
4. 公网 `.env` 使用 SESSION_COOKIE_SECURE=true、APP_REGISTRATION_ENABLED=false，HTTPS 反代后环回绑定；本地 HTTP 启动器显式使用 false/true。不要覆盖私有 `.env`。
5. 验收 GET /api/auth/csrf、登录后 token 轮换、关闭注册、会话读写、停止、同键重放、ADMIN metrics；抓取首页和静态资源确认安全响应头。外部脚本也必须接入 CSRF。
6. 本轮没有部署现有运行栈、没有执行真实聊天模型 E2E；上线验收仍须验证真实 Worker/LLM 以及正式域名 TLS/Cookie 行为。

## 12. 第六轮增量（2026-09-17：轮次安全链路 + CSRF + 工程化）部署约束

本轮（见 docs/REPAIR_REPORT.md）引入了会改变部署方式的约束，升级/回滚前必读：

1. **前后端必须同版本部署**：CSRF 已启用（session 绑定 token，登录后轮换）。旧前端 + 新后端
   的组合会因缺 `X-CSRF-TOKEN` 被拒（403 CSRF_INVALID）。整包换版（同时替换 frontend 与
   backend 镜像）是唯一安全顺序。
2. **新增表 `psych_chat_turn`**（局部唯一索引实现"每会话仅一个生成中轮次"+ request_hash 幂等）。
   DDL 由 initializeSchema 幂等追加，无需手工 SQL；**回滚须先停新实例**——旧实例不认识新表，
   但新实例写入的 turn 行会残留（无害但应知）。
3. **必须 clean 构建**：`target/` 残留曾导致知识库 1654 个文档重复加载（启动时长翻倍）。
   本地构建用 `mvn clean package`，容器构建不受影响（多阶段构建自带干净上下文）。
4. **CI 首跑**：`.github/workflows/ci.yml` 为本轮新增，push 后首次实际运行。Java job 起
   postgres:16 service 并注入 `TEST_DATABASE_URL`——8 个真实数据库事务测试只在有该变量时
   执行（无变量时计入 skipped，属预期）；本地复现方式见 `ChatTurnPostgresTest` 类注释。
5. **SafetyOutputGuard 权衡记录**：PASSIVE/IMMINENT 轮次的回复改为整体缓冲、检查后一次性
   下发（修复"危险文本先输出后检查"）——代价是高风险轮次无流式打字效果。这是安全优先的
   刻意选择，不要为恢复流式而回退缓冲。
6. **应用层安全头**（本轮补齐）：Spring Security `headers()` 现输出 nosniff / DENY /
   Referrer-Policy / HSTS，与 nginx `security-headers.conf` 双层兜底；CSP 仍未启用（v-html
   与内联场景需单独设计），上 TLS 后建议一并评估。

## 单副本启动门禁（2026-09-20）

Java 后端仅支持 `APP_DEPLOYMENT_MODE=single`、`APP_REPLICA_COUNT=1`（默认值）；其他模式、空值、非整数、零、负数及任何不等于 1 的副本数都会拒绝启动。`DkAiAgentApplication.main` 首先检查环境变量，再于 Spring 环境准备事件中复核 YAML/profile/命令行最终值；拒绝发生在 Bean 初始化前，避免启动时全库 RUNNING 恢复及向量库灌注先执行。

`APP_INSTANCE_ID` 可选，空白或未配置时每次进程启动生成新 UUID；显式值允许 1..128 位 ASCII 字母、数字、点、下划线、冒号和连字符。解析后的值供启动日志和 `app.deployment.instance-id` 观测使用，不进入咨询正文，也不是分布式锁或 fencing token。不要在所有实例中复用固定默认 ID。

部署与升级必须先停止旧 Java 后端、确认进程完全退出，再启动新后端，接受短暂中断；禁止重叠滚动发布、双实例蓝绿切流和 `--scale backend=2`。Compose 变量只是部署声明，不能测量真实副本数：两个连接同一数据库的实例即使都声明 single/1，门禁也无法自动发现。

真正多副本仍需共享 Session/跨实例吊销、全局限流、turn lease/fencing、记忆整合协调和按所有权恢复 RUNNING 轮次；sticky session 与 instance ID 均不能替代这些能力。本次门禁和纯单元测试已添加，但本次未运行测试或真实数据库验证。
## 13. 备份恢复 SOP（2026-09-20 本地全流程演练通过）

工具链：`manage.sh backup`（age X25519 公钥流式加密，明文不落盘）→ 三件套
（`.dump.age` 密文 + `.sha256` + `.manifest`）→ `manage.sh verify-backup` 校验 →
`age -d` 解密 → `pg_restore` 恢复。演练结果（本地容器，真实生产包脚本）：

- 加密：密文以 `age-encryption.org/v1` 头起始，密文/校验/manifest 三件套齐全，
  临时文件零残留（明文仅存在于管道）；
- 篡改拒绝：翻转密文 1 字节后 `verify-backup` 立即以"SHA-256 校验失败"拒绝；
- **恢复**：解密 → 恢复到全新 PG 容器 → conversations=9 / messages=30 / users=2
  与源库逐表一致。

日常 SOP：cron 每日 backup；私钥**不存服务器**（离线保存）；恢复演练每季度一次
（恢复到临时容器比对行数即可，不必覆盖生产）。

## 14. 第七轮增量（2026-09-20：本地闭环收尾——备份演练、RAG 换型与工具修复）

- **eval 工具适配生产安全配置**：本地验收栈与生产同配（prod profile + Secure Cookie），
  httpx 按规范拒绝回传 Secure cookie 导致 403/401——改用 response hook 手动跟随
  JSESSIONID（覆盖登录 changeSessionId 轮换）；judge 输出加 JSON 容错（剥代码围栏 +
  重试，失败降级为跳过而非误报 ERROR）。
- **RAG embedding 换型**：all-MiniLM-L6-v2 → multilingual-e5-small（384 维不变）。
  离线对比 + Java 基线复核：Document Hit@4 **0.04 → 0.72**（18 倍）；阈值按分数分布
  校准 0.3 → 0.87（e5 下正负例完全分离：正例 p5=0.886 vs 负例 max=0.863）。
  详见 `eval/RAG_BASELINE.md` 第二轮测量。
- **知识库幽灵副本清理**：生产库曾 1670 条（"大冰连麦案例-*"历史改名残留 ×2），
  清理 819 条后重灌 851（835 案例 + 16 框架）。
- **部署注意（重要）**：compose 与 application.yml 的 `ONNX_EMBEDDING_MODEL_URI`
  默认值已同步为 e5——历史上只改一侧会造成 tokenizer/模型词表不匹配的崩溃循环。
  首次启动新包会自动全量重灌向量库（e5 推理 851 文档约 8 分钟，readiness 期间
  拒绝流量属预期）；ONNX 模型 470MB 首次下载需数分钟。
- 新增 `docs/REMOTE_GO_LIVE.md`（正式 TLS/生产验收单/加密决策/多副本框架）。
