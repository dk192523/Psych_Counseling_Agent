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

- CSRF token 全链路尚未启用；
- 注册默认开放；
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
