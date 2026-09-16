# 改进 Backlog（2026-09-02 全面评估沉淀）

> 来源：对话内核第二期前的三路全面扫描（对话体验 / 架构安全 / RAG 数据层）。
> 本文档记录**已识别但本轮未实施**的改进项。排序依据：对真实使用的影响 × 实施成本。
> 单副本扩展前必读第 6 节。

## 1. 安全加固包（公网部署前必做）

| 项 | 现状 | 建议 |
|---|---|---|
| CSRF | `SecurityConfig` 显式 disable | SSE+Cookie 场景可用 `CookieCsrfTokenRepository.withHttpOnlyFalse()` + 前端读 XSRF-TOKEN 回传；或至少对状态变更端点（注册/登录/删除）启用 |
| 安全响应头 | 无任何 headers() 配置 | 加 `X-Content-Type-Options: nosniff`、`X-Frame-Options: DENY`、CSP（限制 script-src）、HSTS（HTTPS 后） |
| Session Cookie | `secure` 默认 false | HTTPS 部署后置 true（.env 已支持 `SESSION_COOKIE_SECURE`） |
| 注册开关 | 默认开放 | 加 `app.registration.enabled` 配置，公开部署时关闭或改邀请码 |
| 限流多副本 | 注册/登录/聊天限流全进程内 | 上多副本前换 Redis 或 sticky session；单副本无影响 |

## 2. 可观测性

- **MDC/requestId 贯穿**：requestId 目前是代码内传参，仅 MyLoggerAdvisor debug 级打印。建议 logback 配置 MDC，requestId/ownerId/chatId 自动附加到每行日志。
- **危机审计落盘**：本轮已加结构化日志（IMMINENT=WARN / PASSIVE=INFO，无正文）。如需更强的追溯，可加独立审计表（注意：与"正文不留存"的隐私原则权衡）。
- **LLM usage 统计**：Spring AI ChatResponse 携带 usage metadata，当前未解析。建议在 chatClient 外层记录每轮 prompt/completion tokens + 耗时，聚合到 actuator metrics。
- **Micrometer + Prometheus**：actuator 已有，仅暴露 health,info；加 prometheus registry 可观测 QPS/延迟/限流触发数。

## 3. RAG 检索升级

- **检索质量评测缺失（优先级最高）**：先建 golden 问题集（30-50 条：问题→应命中的案例编号），跑 recall@k / MRR 基线，再谈换模型。没有基线，任何换型都是盲调。
- **Embedding 模型**：现用 all-MiniLM-L6-v2（384 维，本地 ONNX，零成本但中文语义偏弱）。候选：BAAI/bge-m3 或 text2vec-large-chinese（需要 API 或更大本地模型 + 重新灌库，pgvector 维度要改）。
- **切分策略**：当前按 `---` 水平线整节入库（`MyTokenTextSplitter` 存在但未使用），长节可能超 embedding 有效窗口。建议 token 切分 + overlap 试点对比。
- **fast 模式混合检索**：fast 是纯向量 topK=4/threshold=0.3；worker 的 BM25 只服务 episodes。可把 worker 的 jieba+BM25 用于 fast 候选重排（一次 worker 调用，需评估延迟）。
- **HNSW 参数**：未设 m/ef_construction（Spring AI 默认）；835 文档规模下影响小，扩库后调。

## 4. 数据治理

- `searchRecallCandidates` 用 `content ILIKE '%kw%'` 全表扫描——数据量大后加 `pg_trgm` GIN 索引或 tsvector。
- 墓碑表无限增长（代码注释已声明可按 deleted_at 清扫）——量大后加定时清理。
- 会话正文明文存储——如需加强：pgcrypto 列级加密（代价是 ILIKE 检索失效，需权衡）。
- 无"导出我的全部数据"端点（GDPR 式）——前端已有单会话导出，可加全量导出 API。
- 备份：manage.sh 的 backup 是手动子命令，无定时无轮转——上 cron + 保留策略。

## 5. 评测扩展

- eval/cases.yaml 现有 7 条；补 B3（连击三轮构造难自动化，可预置历史消息后测最后一轮）、D4/D5、E2-E4 深度模式组。
- judge 的 model 硬编码 `deepseek-chat`（run_eval.py），与主服务模型配置不同源——统一读配置。
- CI 集成：GitHub Actions 跑 mvn test + pytest（不起 LLM）；eval 仍手动。

## 6. 单副本扩展前检查单（多副本部署时逐项处理）

| 内存态 | 位置 | 多副本后果 | 迁移方向 |
|---|---|---|---|
| ChatMemory 窗口 | CounselingApp（InMemoryChatMemoryRepository） | 各副本窗口不一致，上下文错乱 | 换 JDBC ChatMemoryRepository 或按会话哈希粘性路由 |
| 会话注册表 | ActiveSessionService | 登出/封禁只对本实例生效 | Redis pub/sub 或共享存储 |
| 注册/登录/聊天限流 | RegisterThrottleService / LoginAttemptService / ChatRateLimitService | 限额 ×N | Redis 滑动窗口 |
| 整合去重 | ConversationMemoryService.consolidationsInFlight | 可能重复整合（digest CAS 已兜底正确性，只浪费） | 可接受，或 Redis SETNX |
| 危机模板变体游标 | CrisisResponse AtomicLong | 变体可能重复（无害） | 忽略 |

## 7. 其他低优先级

- 前端零测试（无 vitest）——组件逻辑渐复杂后补关键路径；
- 无 OpenAPI 文档与 API 版本化；
- `MyTokenTextSplitter` 已写未用（死代码，接入或删除）；
- prompt 的 few-shot 示例可按阶段动态选择（当前静态三组，够用）。
