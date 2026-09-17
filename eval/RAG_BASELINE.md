# 检索基线（2026-09-17）

这是当前生产 Markdown 加载器和本地 Transformers 嵌入模型的实测起点。结果不理想，不能用工程单测通过替代它。

| 参数/指标 | 值 |
|---|---|
| 输入 | rag_seed.json：25 条改写查询、5 条无关查询 |
| 标注 | 根据源案例创建的种子标签，尚未经独立人工相关性审核；没有穷尽其他可能相关案例 |
| 语料 | 835 个 Markdown Document，指纹 abc35ec23e9d4722 |
| 模型 | 生产 application.yml 中固定的 all-MiniLM-L6-v2 ONNX，384 维 |
| 检索 | 精确余弦，topK=4，threshold=0.3 |
| Recall@4 | **0.04（25 个来源案例中命中 1 个）** |
| MRR@4 | **0.02** |
| 无结果识别率 | **0.00（0/5）** |

完整逐条排名、相似度、案例编号、配置和时间戳见 [rag_baseline.json](rag_baseline.json)。首次运行误包含 target/classes 中残留的旧名称文档，已使用干净构建重跑；当前报告只包含 835 文档的结果。

## 复现

从 dk-ai-agent 目录执行；需要 Java 25，首次可能下载公开 ONNX 模型，不调用聊天模型、不使用 API 密钥、不需要数据库：

```powershell
$env:RUN_RAG_BASELINE='true'
.\mvnw.cmd clean test '-Dtest=RetrievalBaselineTest'
Remove-Item Env:RUN_RAG_BASELINE
```

Linux 对应：`RUN_RAG_BASELINE=true bash mvnw clean test -Dtest=RetrievalBaselineTest`。

测试使用实际 `CounselingDocumentLoader` 和生产 YAML 模型/tokenizer 配置，计算精确余弦排名，输出到 eval/rag_baseline.json。首个基线不设虚构的质量通过阈值：测试完成只表示评测运行成功，**不表示检索质量合格**。

## 解释边界与修改方案

- 这是来源案例命中评估；语义相关的其他案例可能未被标签覆盖，因此不能解释为真实用户总体准确率。
- 基线不覆盖 pgvector HNSW 近似索引、QueryRewriter、深度规划/重排、逐字稿补充和最终回答质量。
- 5 条负例均出现结果，说明当前阈值在这批无关输入上没有有效拒绝；不能只看正例召回。
- 下一步先做独立相关性标注与保留测试集，再比较中文/多语种模型、词法混合检索、按 tokenizer 窗口切块和阈值校准，同时测延迟及误拒绝。模型替换需要维度兼容与向量重建验证。
- 不应直接用这 30 条种子反复调参后宣布提升；种子数据适合回归排查，不能同时充当开发集和独立验收集。
