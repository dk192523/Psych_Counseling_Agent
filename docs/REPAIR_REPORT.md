# 全面修复记录与质量结论

验证日期：2026-09-17。代码基点：`f63f5ea`。本轮修改留在工作区，未提交、未推送，也未替换现有应用容器。用户已有的 `.claude/` 未改动。

## 结论

本轮已修复主要工程缺陷，并补上可以复现的验证手段。不能据此宣布“全部问题解决”或“可以直接公网商用”：检索实测偏弱，真实聊天模型 E2E、隐私存储加密和多副本能力仍未完成。

原 B+ 报告的方向部分正确，但证据有明显失真。工程分层、降级和行为测试确实有价值；“294 全绿”“零安全响应头”以及只按代码行数评价结构，都不能作为严谨结论。检索与真实业务质量的缺口比报告呈现的更严重。

## 对原报告的核对

| 原说法 | 核对结果 |
|---|---|
| 后端 294 全绿 | 原基线共 294 项，实际 288 通过、6 跳过；跳过不是通过 |
| 零安全响应头 | 不实。原 Spring Security 已提供默认 API 响应头及 HTTPS 下 HSTS；前端静态入口仍需补强，本轮已补 |
| CSRF 禁用、HTTP Cookie 无 Secure | 原代码成立；本轮启用 CSRF，并区分本地 HTTP 与生产 HTTPS 默认值 |
| 功能全部闭环 | 过度乐观。同步入口绕过统一安全链、终态归档失败可能仍表现为成功、只去重用户消息而未去重生成 |
| 前端零测试、无 CI | 原状态成立；本轮新增组件/接口测试和 CI 配置，远端 CI 尚未执行 |
| 检索无评测基线 | 原状态成立；现已建立并运行，结果见下 |
| ILIKE 全表扫描，加索引即可解决 | 表述不准确。查询有 conversation_id 过滤及索引，ILIKE 用于排序；应按真实数据量 EXPLAIN，不能直接假定 trigram 索引会改善 |
| 没有 OpenAPI | 旧清单不准确：项目已有 springdoc/knife4j，生产关闭暴露是有意设计 |
| 文档只有两处漂移 | 不止两处：CSRF、GET 兼容入口、Worker 鉴权、测试矩阵、旧归档时机等均有过时说明，本轮已更新核心说明并标注历史段落 |

## 已修复

### 1. 聊天一致性与安全

- 同步与 SSE 请求统一经过 `CounselingTurnPipeline`，同步请求也保留深度模式参数；所有权、限流、输入合法性和危机处理共同生效。
- 请求正文非空、最多 4000 个 Unicode code point；会话和消息标识限制为 1–64 位合法字符，进入模型前拒绝超限请求。
- 新增 `psych_chat_turn`：同会话最多一个 RUNNING；同键不同内容/模式返回 409；完成轮次直接重放，不重复调用模型。
- 回答与终态在同一事务内提交，成功后才发送 done；保存失败返回 error。用户停止和上游错误会尝试保存已发出的部分文本。
- 已保存的部分回答不可变；相同键重试返回部分文本与 partial 错误，用户用新消息继续。空失败允许同键重试。
- 空回复、缺失 done、重复订阅、持续输出超时/超长都有明确终止逻辑。生成上限 180 秒，预留恢复窗口 5 分钟。
- 对已识别的高风险轮次，完整输出检查先于文本下发；危险应和命中时替换为固定资源文本，实际输出与归档保持一致。规则不构成临床安全保证。
- 每轮模型窗口从已提交历史重建，避免重试重复注入当前用户消息；删除旧 doFinally 成功归档、水合脏标记及三个未使用实验类。

### 2. Web 安全与部署

- 启用会话绑定的 CSRF token，登录/注册/SSE/删除等写操作均校验；登录后轮换，前端和 eval 重新获取。真实 masked token 往返与旧 token 失效已测试。
- 生产默认 Secure Cookie、注册关闭；本地 HTTP 启动器显式保留可登录配置。服务器示例改为环回地址 + HTTPS 反代，已有私有 `.env` 未被覆盖。
- Nginx 补充 CSP、nosniff、DENY、Referrer-Policy 和 Permissions-Policy；首页及错误页实际响应已验证。
- 携带凭据的 CORS 禁止通配来源；管理员指标接口对普通用户返回 403。

### 3. 前端与工程化

- Vite 4 升至 8，更新 Vue、Axios、DOMPurify、head 管理等依赖并重新生成锁文件；Node 要求 `^22.22.2 || >=24.15.0`，本轮本机 Node 24.19 与 Node 24 Alpine 容器均验证。
- SSE 和 CSRF 传输逻辑独立成模块；处理 UTF-8 分片、异常 EOF、HTTP 状态和 token 失效。
- 修复会话切换/创建/删除的过期响应覆盖，保留服务端错误提示，部分回答不自动重试，手动重试沿用原消息键。
- 新增 ESLint、Vitest 与 GitHub Actions：Java + PostgreSQL、Python、前端三个任务，无需真实模型密钥。

### 4. 评测、观测和数据保留

- eval 实际执行 max_chars，拒绝空回复/不完整流/错误事件/非布尔 judge；未执行 judge 明确显示 SKIP，保存完整回复与实际模式、fallback 元数据。
- 机器对话用例增至 9 条，明确其与人工用例的改写关系；历史报告加失效说明，未伪造新聊天评测结果。
- 增加请求 ID、轮次数量/耗时/归档失败指标和 ChatClient usage 聚合计数。无正文指标标签；并非全链路计费统计，也不代表所有异步线程 MDC 已贯通。
- 重放缓存保留七天并按小时清理，避免永久重复保存回答；会话删除级联清除缓存。主历史/摘要仍遵循原保留规则。

## 实际验证

| 验证 | 结果与边界 |
|---|---|
| Java 最终回归 | **321 项：314 通过，0 失败，0 错误，7 跳过** |
| 真实 PostgreSQL | 上述通过项中含 **8 项**隔离事务测试；每项随机 schema，完成后清除，不使用现有业务库 |
| Java 跳过项 | 6 项原真实外部服务集成 + 1 项 opt-in 检索基线；检索基线已在前一轮干净构建中单独执行成功 |
| Python | **41 通过**：Worker 30 + eval 11；有 Starlette/httpx 弃用提示，无测试失败 |
| 前端 | **13 通过**，ESLint 通过；覆盖 IME、停止、重发键、过期会话响应、错误提示、XSS、CSRF、SSE |
| 构建与依赖 | 前端本机构建、Docker 构建通过；容器 npm ci 审计 **0 项已知漏洞**，不等于所有依赖绝对安全 |
| Nginx | `nginx -t` 通过；隔离容器首页 200 和模拟后端不可达 502 都具备安全头。未据此声称完整 API 链路通过 |
| 检索 | 835 文档、25 条来源案例查询 + 5 条无关查询；Recall@4 **0.04**，MRR@4 **0.02**，无结果识别 **0/5** |
| Git | `git diff --check` 通过；没有自动提交或推送 |

最终 Java 回归完成于 2026-09-17 16:10（Asia/Singapore）。Docker Desktop 后续出现临时通信文件故障，期间一轮数据库测试因连接拒绝失败；没有将该轮计为通过。最终使用仅监听环回随机端口的 PostgreSQL 16.15 免安装实例重新跑完整测试，获得上表结果，随后正常关闭进程。未注册系统服务，未连接现有业务库。免安装包来源见 [EDB 官方二进制下载](https://www.enterprisedb.com/download-postgresql-binaries)。Docker 自身的间歇启动故障不属于本次项目代码修复结果。

重跑核心测试（项目根目录，先配置 Java 25；前端使用满足 engines 的 Node）：

```powershell
# TEST_DATABASE_URL 指向专用测试 PostgreSQL，禁止填写生产库。
$env:TEST_DATABASE_URL='jdbc:postgresql://127.0.0.1:TEST_PORT/repair_test'
$env:TEST_DATABASE_USER='postgres'
$env:TEST_DATABASE_PASSWORD='repair_test_only'
Set-Location dk-ai-agent
.\mvnw.cmd clean test
Set-Location ..
$env:PYTHONPATH='ai-worker/src'
.\ai-worker\.venv\Scripts\python.exe -m pytest ai-worker/tests eval
Set-Location dk-ai-agent/dk-ai-agent-frontend
npm ci
npm run lint
npm test
npm run build
```

## 上线与剩余工作

1. **必须前后端一起更新**，旧客户端不发送 CSRF token 会收到 403；登录成功后需要重新获取 token。
2. **只支持单副本、停旧再起新**。启动会恢复遗留 RUNNING；不能进行两个实例重叠的滚动部署。现有 `.env` 必须明确检查 Secure Cookie、注册开关和反代配置。
3. 使用 **clean 构建**。本轮发现 target/classes 残留已改名的旧知识库文件，导致 1654 文档重复加载；清理构建后恢复为 835。
4. 检索种子集需独立人工相关性标注；当前基线暴露问题，但尚未换模型、改维度或重建生产向量库。详见 [RAG_BASELINE](../eval/RAG_BASELINE.md)。
5. 正文与摘要仍是数据库明文；磁盘/备份加密、密钥管理与列加密迁移没有在本轮实施。真实 LLM/Worker E2E、正式 TLS 行为和危机流程需要部署验收。
6. 本轮新增表为增量建表，不改写现有正文。回滚必须先停止新实例；旧版本可忽略新表，但会重新失去本轮的安全及一致性保障。

剩余事项按优先级见 [IMPROVEMENT_BACKLOG](IMPROVEMENT_BACKLOG.md)。
