# RAG 离线评测契约与 seed 回归基线

`rag_seed.json` 的 30 条样本是 **source-grounded seed 回归集，不是人工 golden set**：25 条按来源案例改写的查询和 5 条预设无关查询，尚未经独立人工相关性审核，正例标签没有穷尽其他相关案例。保留查询和来源 slug，不以此次契约修改宣称模型质量提升。

## 历史实测结果（2026-09-17）

| 参数/指标 | 值 |
|---|---|
| 语料 | 835 个 Markdown Document，指纹 `abc35ec23e9d4722` |
| 模型 | all-MiniLM-L6-v2 ONNX，384 维；具体 URI 见报告 |
| 检索 | 精确余弦，topK=4，threshold=0.3 |
| Document Hit@4 | **0.04（25 条正例查询中 1 条命中来源 slug）** |
| Document MRR@4 | **0.02** |
| 正例拒绝率 | **0.00（0/25）** |
| 预设负例返回结果率 | **1.00（5/5）** |
| 预设负例拒绝率 | **0.00（0/5）** |
| Case Hit@4 / Case Recall@4 / Case MRR@4 | 未测量，`null` |

[rag_baseline.json](rag_baseline.json) 保留原时间戳、Document 排名、相似度和案例编号。本次只迁移报告口径，**没有重跑模型**。旧运行没有保存完整候选序列，不能从 4 个 Document 可靠重建“全量候选先按案例去重再取前 4”的结果。新案例指标、旧运行未记录的 tokenizer URI、seed 哈希及耗时均为 `null`；拒绝率仅按已保存候选计数计算。首次运行曾混入 `target/classes` 的残留旧名称文档，历史报告已由干净构建重跑为 835 文档。

## 指标定义（报告 schema v2）

设正例查询集合为 P，预设负例查询集合为 N。每条正例的 E 是 `expected` 按 slug 去重后的集合，可以有多个标签。先保留余弦分数 ≥ 0.3 的全部 Document，再建立两个独立排名：

- **Document 排名 Dₖ**：分数降序；同分按 filename、status、完整正文的 Java String 字典序升序，取前 K 个。相同来源和正文的重复 Document 对标签指标等价。Document 的随机 ID 和加载顺序不作为有意义的排序依据。
- **案例排名 Cₖ**：从全部过阈值 Document 中提取 slug，相同 slug 合并，案例分数取其最高 Document 分数；按分数降序、slug 升序取前 K 个。没有 slug 的 Document 不参与案例排名。一个 Document 若包含多个 slug，各 slug 共享该 Document 分数。这只是离线归并口径，并不证明该正文的每个案例都语义相关。

| 报告字段 | 定义及分母 |
|---|---|
| `document_hit_at_4` | 对 P 计算：前 4 个 Document 的任一 slug 与 E 相交记为 1，否则 0，再取平均。这里的“document-level”说明排名单位，不代表有完整 Document 相关性标签。 |
| `document_mrr_at_4` | 对 P 计算：D₄ 中首个包含 E 中 slug 的 Document 的排名倒数；未命中记 0，再取平均。 |
| `case_hit_at_4` | 对 P 计算：C₄ 与 E 有交集记为 1，否则 0，再取平均。 |
| `case_recall_at_4` | 对 P 计算每条查询的 `|C₄ ∩ E| / |E|`，再取宏平均。只衡量已知标签覆盖率；多标签查询每条等权。 |
| `mrr_at_4` | **案例排名**：C₄ 中首个命中 E 的案例排名倒数，未命中记 0，再对 P 取平均。 |
| `positive_rejection_rate` | 正例中一个过阈值 Document 都未返回的查询数 / `|P|`。返回其他案例不算拒绝，也不算命中标签。 |
| `negative_false_hit_rate` | 预设负例中至少返回一个 Document 的查询数 / `|N|`。这里的 false hit 仅是 seed 负例约定，尚非人工审核的语义误召回。 |
| `no_hit_accuracy` | 兼容旧字段：预设负例没有返回 Document 的比例；当 N 非空时等于 `1 - negative_false_hit_rate`。 |

所有汇总指标在分母为 0 时输出 `0.0`，不输出 NaN/Infinity。`null` 仅用于历史数据未采集或无法补算，不等于 0。`unjudged` 查询可以记录候选，但不进入上述正负例分母；没有出现在 E 中的候选也不能自动当作负例，因此不报告 Precision、候选级准确率或穷尽相关性的 Recall。

**兼容说明：**旧 `recall_at_4` 实为 Document Hit@4，继续保留为 `document_hit_at_4` 的废弃别名，不得显示为 Recall。旧 `first_relevant_rank` 保留为 `first_relevant_document_rank` 的别名，0 表示未命中。schema v1 的 `mrr_at_4` 使用 Document 排名，schema v2 改用案例排名，旧值迁至 `document_mrr_at_4`；比较报告必须检查 schema 和排名单位。新 `case_recall_at_4` 不可直接与旧 `recall_at_4` 比较。单标签时案例 Hit 和案例 Recall 数值相同，定义仍不同。

## Seed 前置校验与运行元数据

seed schema v2 要求根对象包含 `schema_version`、非空 `label_status`、`sample_counts` 和 `cases`。`sample_counts` 显式声明 positive、negative、unjudged 数量，必须与逐条计数一致。测试在初始化模型前验证：

- `cases` 为数组，保留 `assertEquals(30, seed.cases.size())`，直接校验固定回归集结构。
- 每条为对象，id 唯一且无首尾空格，id/query 为非空字符串，`expected` 为字符串数组，slug 格式为 `20xx-xx-xx-call-xx`，每个期望 slug 必须存在于实际加载语料中。
- `sample_type` 必须显式为 positive、negative 或 unjudged。positive 的 expected 不可为空，negative/unjudged 的 expected 必须为空；仅空数组不会自动变成负例。
- expected 的重复 slug 在计算指标前归一为集合。多标签无需改变指标定义。30 条是固定 seed 回归约束，后续独立人工测试集应单独建文件，不覆盖它。

新运行报告记录模型和 tokenizer URI、维度、生产指纹函数生成的知识库 fingerprint、Document 数量、topK/threshold、排序规则、seed schema/原始文件 SHA-256/label_status、样本计数。`total_elapsed_ms` 从测试入口计至报告序列化前，包括加载、可能的模型下载、语料和查询 embedding；`query_elapsed_ms` 包含单条查询 embedding、全库余弦、排序归并和结果组装，不包含语料 embedding。不同缓存状态的总耗时不宜直接比较。报告只输出查询 id、标签、来源文件名、文档内容哈希、分数和排名，不复制查询或语料正文。

## 复现

从 `dk-ai-agent` 目录执行；需要 Java 25，首次可能下载公开 ONNX 模型，不调用聊天模型、不使用 API 密钥、不需要数据库：

```powershell
$env:RUN_RAG_BASELINE='true'
.\mvnw.cmd clean test '-Dtest=RetrievalBaselineTest'
Remove-Item Env:RUN_RAG_BASELINE
```

Linux：`RUN_RAG_BASELINE=true bash mvnw clean test -Dtest=RetrievalBaselineTest`。

实际调用 `CounselingDocumentLoader` 和生产 YAML 模型/tokenizer 配置，精确余弦评测后覆盖 `eval/rag_baseline.json`。测试完成只表示结构与运行成功，**不表示质量合格**；没有新增质量通过阈值，也没有修改生产模型、切块或检索参数。范围不覆盖 pgvector HNSW、QueryRewriter、深度规划/重排、逐字稿补充与最终回答质量。

## 下一步人工标注

[rag_review_template.json](rag_review_template.json) 只有待填写模板，不能直接作为已标注 seed 导入。独立人工数据应另建文件并固定语料 fingerprint，schema 包含：

- 查询 `id/query/split`（dev 或 test），`sample_type`、`expected`（审核通过的所有已知相关 slug）、`judgment_scope`（评审候选池范围，或明确的全库审核范围）。
- `candidate_judgments` 每项为 `{slug, relevance, reviewer_id, rationale}`；relevance 为 relevant、irrelevant 或 unjudged。候选缺少标注默认 unjudged；未被 expected 覆盖不意味着 irrelevant。
- `review_status` 为 pending、reviewed 或 adjudicated，配套 reviewers、adjudicator、reviewed_at 和 notes。空 expected 且 pending 仍是 unjudged；只有人工明确确认查询在约定语料/范围内无相关内容，才可设为 negative。

先由独立评审给出相关性判断，处理分歧、确认多标签和负例依据，再冻结保留测试集。种子适合回归排查，不应反复用于调参后宣称独立验收提升。后续模型、混合检索、切块或阈值实验应在另行授权的范围开展。
