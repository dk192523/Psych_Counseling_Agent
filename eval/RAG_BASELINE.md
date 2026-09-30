# RAG 离线评测契约、seed 回归基线与路由 golden 候选集

`rag_seed.json` 的 30 条样本是 **source-grounded seed 回归集，不是人工 golden set**：25 条按来源案例改写的查询和 5 条预设无关查询，尚未经独立人工相关性审核，正例标签没有穷尽其他相关案例。保留查询和来源 slug，不以此次契约修改宣称模型质量提升。

**最新测量（2026-09-30）仍是 multilingual-e5-small 的 source-grounded seed Document Hit@4 = 0.72（18/25），不是人工 golden 的 Recall。** 本轮语料为 851 个 Markdown Document，fingerprint 为 `3141c0e91ecd0efa`；Case Hit@4 和 Case Recall@4 均为 0.72，负例误命中率仍为 1.00（5/5）。这些是种子回归数字，不是人工相关性评测或真实用户准确率。报告中的 `recall_at_4` 只是旧字段别名。2026-09-17 的 0.04 保留在下表作为历史测量，不能当作当前结果。[routing_golden_v1.jsonl](golden/routing_golden_v1.jsonl) 新增 32 条原创路由标注候选，尚待独立人工审核，也尚无路由实测分数或检索排序质量分数。

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

以上是历史结果；当时的契约迁移没有重跑模型。旧运行没有保存完整候选序列，不能从 4 个 Document 可靠重建“全量候选先按案例去重再取前 4”的结果，因此当时新增案例指标及未采集元数据记为 `null`。首次运行曾混入 `target/classes` 的残留旧名称文档，历史报告已由干净构建重跑为 835 文档。当前 [rag_baseline.json](rag_baseline.json) 已由 2026-09-20 的 e5 测量覆盖，不再保存这份 MiniLM 历史 JSON；历史数值以上表为记录。

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

---

## 第二轮测量（2026-09-20：embedding 换型 multilingual-e5-small）

### 动机与决策依据

离线对比实验（`eval/embedding_shootout.py`，语料 = 生产向量库导出的 851 文档，与线上检索同分布）：

| 配置 | Document Hit@4 | Document MRR@4 | 正例 top1 区间 | 负例 top1 区间 |
|---|---|---|---|---|
| all-MiniLM-L6-v2（换型前生产） | 0.00 | 0.00 | 0.63–0.76 | 0.68–0.75 |
| e5-small plain（本轮采用） | **0.72** | **0.60** | 0.887–0.935 | 0.838–0.854 |
| e5-small + query:/passage: 前缀 | 0.72 | 0.61 | 0.886–0.935 | 0.835–0.863 |

- MiniLM（英文模型）对中文语料正负例分数**完全重叠**——任何阈值都不可分，这就是
  第一轮"负例零拒绝"的根因；
- e5 下正例 p5（0.886）与负例 max（0.863）**完全分离**，生产 `similarityThreshold`
  由 0.3 校准为 **0.87**（全拒 5 负例、放行 ≥95% 正例；5 条负例是小样本，窗口
  [0.865, 0.885] 内取点，泛化留有余量）；
- 前缀模式差异仅 0.01（document 级），首轮落地采用 plain（零代码改动）；
  case 级评测建立后再评估是否值得引入前缀包装。

### 落地变更（生产链路，Java 基线复核 `rag_baseline.json` 同步更新）

- `application.yml`：model-uri / tokenizer 换 e5 远程 URI；`app.rag.embedding-version`
  → `transformers-multilingual-e5-small-384-v2`（触发全量重灌）；维度 384 不变；
- `CounselingApp.buildRagAdvisor`：similarityThreshold 0.3 → 0.87（校准依据见上表）；
- **顺带清理幽灵副本**：生产库曾达 1670 条 = 同内容两套文件名（"大冰连麦案例-*"为
  历史改名残留），检索在与幽灵竞争；手动 DELETE 819 条后重灌为 851（835 案例 + 16 框架）；
- 教训记录：compose 与 yml 的 `ONNX_EMBEDDING_MODEL_URI` 默认值必须同步换——只改 yml
  时 e5 tokenizer（词表 25 万）喂 MiniLM 模型（词表 30522）会 Gather 越界崩溃循环。

### 复核数字（Java `RetrievalBaselineTest`，生产 loader + 生产模型）

| 指标 | 换型前 | 换型后 |
|---|---|---|
| document_hit_at_4 | 0.04 | **0.72** |
| document_mrr_at_4 | 0.02 | 0.563 |
| case_hit_at_4 | null（未测） | 0.72 |
| eval 端到端 | 9/9 | 9/9（换型后复验） |

该结果仍属 source-grounded seed（标签来自案例出处，未独立人工审核）：**0.72 是 Document Hit@4，不是人工 golden 的 Recall，也不是真实用户准确率。** 2026-09-20 的换型测量当时记录 835 个 Document。2026-09-30 已通过生产 loader 与当前 e5 模型重测，更新后的 JSON 记录 851 个 Document、fingerprint `3141c0e91ecd0efa`、topK=4、threshold=0.3；`document_mrr_at_4=0.5633`，`case_recall_at_4=0.72`，`negative_false_hit_rate=1.0`。生产相似度阈值仍为 0.87；这个离线 seed harness 固定使用 0.3，因此不能将其结果解读为生产请求表现。新增文档后指标没有显示回归，但 5 条预设负例仍全部返回结果，且正例标签未经人工独立审核。

## 第三次测量（2026-09-30：补入专业摘要后）

使用 `CounselingDocumentLoader`、当前 multilingual-e5-small ONNX 模型和与历史相同的 seed 设置重跑。测量耗时约 72 秒；结果写入 `eval/rag_baseline.json`。

| 指标 | 2026-09-20 | 2026-09-30 |
|---|---:|---:|
| Document 数 | 835 | 851 |
| Corpus fingerprint | `abc35ec23e9d4722` | `3141c0e91ecd0efa` |
| Document Hit@4 | 0.72 | 0.72 |
| Document MRR@4 | 0.5633 | 0.5633 |
| Case Hit@4 / Case Recall@4 | 0.72 / 0.72 | 0.72 / 0.72 |
| 预设负例误命中 | 5/5 | 5/5 |

新增专业材料没有改变这组案例来源查询的汇总命中率。负例全命中说明当前阈值和 seed 负例仍无法证明无关请求会被可靠拒绝。该测量只验证当前语料对旧案例查询的回归情况；专业摘要没有独立的人工相关性查询集，不能由这组数字证明专业知识回答质量。

## 路由 golden v1：供人工审核的第一版标签

[golden/routing_golden_v1.jsonl](golden/routing_golden_v1.jsonl) 有 32 条原创中文合成查询：倾听 5 条、专业知识及政策/危机 8 条、案例 5 条、混合 5 条、考试 4 条、无关 5 条。没有复制外部原文或引入真实个人信息。查询和初始标签由本次自动化协作起草，全部 `review_status=pending`；文件名中的 golden 表示待建设的评测资产，**不能宣称已由人工标注或独立审核完成**。

每条记录包含 `id`、`query`、`expected_route`、`allowed_domains`、`should_retrieve`、`notes`，另用 `review_status` 显式保留审核状态。`allowed_domains` 是该路由完整的检索许可集合，不表示每个域都有相关文档，也不是文档相关性标签：

| expected_route | allowed_domains | should_retrieve |
|---|---|---|
| listen | 空数组 | false |
| professional | psych-knowledge、psych-policy | true |
| cases | psych-cases | true |
| mixed | psych-knowledge、psych-policy、psych-cases | true |
| exam | psych-exams | true |
| irrelevant | 空数组 | false |

域集合对应 `KnowledgeIntentRouter` 的路由边界，比较时忽略域顺序。`psych-prompts` 虽是知识来源域枚举之一，但不允许用户检索。查询的期望路由表达待审核的产品行为，不应根据当前关键词路由的输出反向修改标签以提高分数。普通考试压力和刷题意图区分；危机请求不能被倾诉措辞压成 `listen`。危机样本的 `should_retrieve=true` 仅规定知识访问范围，不意味着等待检索后才能提供即时支持；本集也不评价危机回答的临床适当性。

`validate_golden.py` 只使用标准库，检查 UTF-8/JSONL、必填字段、唯一 id、非空查询、路由与域白名单、完整路由域映射、布尔值及检索开关一致性。重复查询比较时统一 Unicode NFKC、大小写并去除空白；重复域、重复 JSON 键、空行、非标准 JSON 常量和空数据集均拒绝。额外审核元数据可保留。结构校验成功返回 0，文件、数据或参数错误返回 1；**通过校验不是人工审核或质量验收**。

### 人工审核与后续评分示例

在仓库根目录可执行以下结构检查；下列 pytest 命令仅运行离线校验器测试，不执行模型。2026-09-30 已运行 `python eval/validate_golden.py`，并通过完整 Python `ai-worker/tests` 与 `eval` 测试套件；这些结果不代表人工审核或质量验收，既有历史测量保持原口径：

```powershell
python eval/validate_golden.py eval/golden/routing_golden_v1.jsonl
python -m pytest eval/test_validate_golden.py -q
```

人工审核时逐条读 query/notes，确认意图和域边界；有分歧时裁决并记录理由。只在实际审核后改为 `reviewed` 或 `adjudicated`，补充真实的 `reviewers`、`reviewed_at` 和必要的裁决记录，再冻结版本。未审核样本不参与“人工 golden”评分。可导出仅供阅读的审核表，修改仍写回 JSONL：

```powershell
Get-Content -Encoding UTF8 eval/golden/routing_golden_v1.jsonl |
    ForEach-Object { $_ | ConvertFrom-Json } |
    Select-Object id, query, expected_route, allowed_domains, should_retrieve, notes, review_status |
    ConvertTo-Csv -NoTypeInformation |
    Set-Content -Encoding UTF8 eval/golden/routing_review_v1.csv
```

完成审核后，另行接入实际路由运行器，逐查询保存预测 JSONL，例如 `eval/golden/routing_predictions_v1.jsonl`。每条必须含唯一 `id`、`predicted_route`、`allowed_domains` 和布尔型 `should_retrieve`，并配套记录代码版本、运行时间及 golden 文件哈希。**当前未提供或执行该路由运行器，也没有生成预测文件。** 具备这些前提后，可以用以下命令计算路由准确率、域集合完全匹配率和检索开关准确率；这个示例会拒绝尚未人工审核的标签：

```powershell
@'
import json
from pathlib import Path
from eval.validate_golden import validate_golden, ROUTE_DOMAINS, RETRIEVAL_DOMAINS

gold_path = Path("eval/golden/routing_golden_v1.jsonl")
errors = validate_golden(gold_path)
if errors:
    raise SystemExit("\n".join(errors))
gold = [json.loads(line) for line in gold_path.read_text(encoding="utf-8").splitlines()]
if any(row.get("review_status") not in {"reviewed", "adjudicated"} for row in gold):
    raise SystemExit("请先完成独立人工审核，不能将 pending 标签当成人工 golden 评分")
pred = [json.loads(line) for line in Path("eval/golden/routing_predictions_v1.jsonl").read_text(encoding="utf-8").splitlines()]
by_id = {row["id"]: row for row in pred}
if len(by_id) != len(pred) or set(by_id) != {row["id"] for row in gold}:
    raise SystemExit("预测 id 必须唯一并与 golden 完全一致")
for row in pred:
    if row.get("predicted_route") not in ROUTE_DOMAINS or type(row.get("should_retrieve")) is not bool:
        raise SystemExit("预测路由或检索开关格式错误")
    domains = row.get("allowed_domains")
    if not isinstance(domains, list) or any(not isinstance(d, str) for d in domains):
        raise SystemExit("预测域必须为字符串数组")
    if len(domains) != len(set(domains)):
        raise SystemExit("预测域不可重复")
n = len(gold)
print("route_accuracy:", sum(by_id[g["id"]]["predicted_route"] == g["expected_route"] for g in gold) / n)
print("domain_exact_match:", sum(set(by_id[g["id"]]["allowed_domains"]) == set(g["allowed_domains"]) for g in gold) / n)
print("retrieve_accuracy:", sum(by_id[g["id"]]["should_retrieve"] == g["should_retrieve"] for g in gold) / n)
print("forbidden_domain_rate:", sum(not set(p["allowed_domains"]).issubset(RETRIEVAL_DOMAINS) for p in pred) / n)
'@ | python -
```

这些只是路由和访问边界指标，不是检索排序指标。当前文件没有相关 Document/slug 标签或候选相关性分级，**因此尚不能为它计算 Document Hit、Recall、MRR 或 nDCG，质量也尚未被证明**。后续排序评估仍需按 `rag_review_template.json` 固定语料 fingerprint、候选池及评审范围，人工标注相关/不相关/未判断、解决分歧并冻结独立测试集；未判断候选不能直接当作不相关。上面的 Java `RetrievalBaselineTest` 仍只评分 `rag_seed.json`，不会自动读取本路由 golden。
