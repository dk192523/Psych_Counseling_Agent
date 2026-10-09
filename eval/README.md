# 离线评测与人工 RAG golden 门禁

当前尚无生产可用语料和已完成独立人工审核的 RAG golden set。来源仍处于 quarantine/reference/pending；`rag_seed.json` 的 30 条是待独立审核的来源回归样本；`golden/routing_golden_v1.jsonl` 的 32 条均为 pending 路由候选。它们都不能按“已完成人工 golden”评分。

`rag_baseline.json` 与 `RAG_BASELINE.md` 保存历史 seed 测量及当时的参数口径，**不代表现行生产质量**。历史结果未覆盖现行可用生产语料、完整路由/检索/回答链路和独立人工相关性标注。本门禁不修改旧结果、不计算指标，也不自动把候选补到 60 条。

## 状态及命令

`golden/rag_golden_v1.json` 是版本化空模板，`cases=[]`。`annotation_template` 仅用于复制填写，永远不计入样本数。`validate_rag_golden.py` 仅依赖 Python 标准库：

```powershell
python eval/validate_rag_golden.py
python eval/validate_rag_golden.py --require-ready
python -m unittest discover -s eval -p test_validate_rag_golden.py
```

普通校验允许合法的未完成资产，输出 `PENDING`、完成审核数量和待办原因，退出 0；`--require-ready` 对 PENDING 退出 2；格式错误、重复 query、假报 READY 或 reviewed 记录缺标签等退出 1。只有所有快照和标签符合契约、没有 pending 记录、至少 **60 条 fully reviewed**，才输出 READY。READY 是标注资产可用状态，不是检索质量、回答质量或安全性得分。CI 仅运行校验器单测，不要求当前空模板 READY，不执行 benchmark。

本次仅新增实现与测试定义，未运行测试或模型，未宣称测试通过。

## 固定评测快照

`snapshot.corpus` 需要 id/version、corpus 文件及 manifest 的相对路径与原始字节 SHA-256，并明确 `production_eligible=true`。路径相对 golden 文件目录；拒绝绝对路径、上级跳转和逃出该目录的符号链接。文件必须实际存在且哈希匹配。quarantine/reference/pending 语料不能冻结为生产语料。

manifest 为 UTF-8 JSON 对象，包含 `corpus_id`、`corpus_version`、`corpus_sha256`、`production_eligible`、非空 `documents` 和 `chunks`。document 至少含唯一 `id`、检索 `domain` 和内容 `sha256`；chunk 至少含唯一 `id`、所属 `doc_id` 和内容 `sha256`。两者哈希作为标签引用的版本身份；生成 manifest 时应从实际文档/chunk 内容计算，而非手工填入。校验器验证 corpus/manifest 文件哈希、映射与引用关系，不判断许可证据、人工身份真实性或引用语义是否支持事实；这些仍需真实审核，不能用设置布尔值代替来源审批。

`snapshot.chunking` 固定实现、revision、完整 config 与 config_sha256，记录分隔符、长度、overlap、长度单位等实际设置。`snapshot.models` 固定 embedding/tokenizer/answer 的 id、不可变 revision 和 artifact_sha256；reranker 明确 enabled，禁用时三个模型字段必须 null，启用时完整填写。托管模型不能伪造 artifact 哈希；若供应方不能提供不可变可验证版本，该快照暂时无法满足此 v1 严格契约，应保持 PENDING。

`snapshot.retrieval` 固定实现、revision、全部实际 parameters 和 parameters_sha256，至少含 `top_k`、`similarity_threshold`、`distance_metric`、`query_transform`、`index_type`、`index_revision`；阈值需有限数值。如果实际链路还有查询改写、过滤、HNSW、去重、重排或提示词参数，也写入该快照，不能只记录默认 topK。配置哈希使用 `json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)` 的 UTF-8 字节 SHA-256，可用校验器的 `canonical_sha256`。`freeze` 记录真实审核人、带时区冻结时间及代码 revision。更换语料/切块/模型/检索设置后另起版本并重新审核，不继承旧版本质量结论。

## 人工逐条标注

从 `annotation_template` 复制到 cases 后填写真实 query/id，`split=test`。初始 `review_status=pending`；仅在独立审核完成后改成 `reviewed`，有分歧且完成裁决时用 `adjudicated`。不以数量目标制造查询，不将测试代码里的合成 fixture 搬入真实 golden。

| 字段 | 标注要求 |
| --- | --- |
| routing | expected_route 为 listen/professional/cases/mixed/exam/irrelevant；allowed_domains 必须完整匹配既有路由域映射，should_retrieve 与域是否非空一致；禁止 psych-prompts 进入检索域。 |
| answerability | answerable / partially_answerable / unanswerable / not_applicable；非检索查询用 not_applicable。 |
| judgment_scope | exhaustive_corpus / pooled_candidates / no_retrieval，记录候选 doc/chunk ids 与覆盖范围说明。全语料标注需覆盖 manifest 全集；候选池只支持该池口径，不能声称全语料 Recall。未判断不等于不相关。 |
| document_relevance / chunk_relevance | 每个池内候选恰好一次，grade 为 0 不相关、1 弱相关、2 相关可支持部分回答、3 高度相关可直接支持回答；必须提供 rationale。null/unjudged 不能算完成。chunk 必须匹配所属 doc 和路由允许域。 |
| supporting_facts | 逐条事实、唯一 fact id，以及 doc_id/chunk_id/evidence_quote；引用 doc/chunk 必须在池内且 grade >=2。检索后的 answer 必须有事实与引用。澄清/不足说明/拒答等无支持事实时明确空数组。 |
| expected_response | behavior 为 answer / clarify / insufficient_evidence / refuse / crisis_support / listen，填写 required_elements、prohibited_elements、notes。部分可回答需注明证据边界；澄清注明待确认信息；不足说明注明缺失证据；拒答注明原因与可提供的替代帮助；危机场景明确预期求助、安全支持及禁止行为。不能根据有无命中文档自动决定危机行为。 |
| review | annotator、独立 reviewers（不得与 annotator 同一人）、带时区 reviewed_at。reviewed 的 adjudication=null；adjudicated 填裁决 reviewer/reviewed_at/rationale/outcome/resolved=true。记录真实人员与日期。 |

重复 id 或经过 Unicode NFKC、大小写和空白归一化后重复 query 均拒绝。禁止重复 JSON 键、NaN/Infinity、非 UTF-8；未审样本即便填了全部自动标签也不会计入 fully reviewed。60 条门槛不能由修改 `minimum_fully_reviewed_cases` 降低。人工应另外检查任务覆盖、危机样本、查询独立性和数据污染；本校验器不会用结构检查代替这些判断。
