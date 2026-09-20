"""离线 embedding 对比实验（Phase D.1）：MiniLM vs multilingual-e5-small。

复刻生产检索形态：counseling-kb/document/*.md 按 `---` 水平线分节（与
CounselingDocumentLoader 一致），对 eval/rag_seed.json 的 25 正例 + 5 负例
计算 top-4 余弦命中，输出三组配置的对比：
  A. minilm（当前生产，无前缀）
  B. e5（无前缀——e5 未按训练约定使用，作消融对照）
  C. e5 + "query:"/"passage:" 前缀（官方推荐用法）

指标与 RAG_BASELINE.md 同口径：document_hit_at_4、document_mrr_at_4、
负例 top1 分数分布（供阈值校准）。只做 document 级（标签粒度所限）。
"""
import json
import math
from pathlib import Path

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

ROOT = Path(__file__).resolve().parent.parent
KB = ROOT / "counseling-kb" / "document"
MODELS = {
    "minilm": (Path(r"C:/Users/dk/AppData/Local/Temp/embed-test/minilm-model.onnx"), Path(r"C:/Users/dk/AppData/Local/Temp/embed-test/minilm-tok.json"), False),
    "e5_plain": (Path(r"C:/Users/dk/AppData/Local/Temp/embed-test/e5-model.onnx"), Path(r"C:/Users/dk/AppData/Local/Temp/embed-test/e5-tok.json"), False),
    "e5_prefix": (Path(r"C:/Users/dk/AppData/Local/Temp/embed-test/e5-model.onnx"), Path(r"C:/Users/dk/AppData/Local/Temp/embed-test/e5-tok.json"), True),
}


def load_documents():
    """直接加载生产向量库导出的文档（docker exec psql 导出 jsonl），
    与线上检索 100% 同分布——比复刻切分逻辑可靠，且剔除"大冰连麦案例"幽灵副本。"""
    path = Path(r"C:/Users/dk/AppData/Local/Temp/embed-test/docs.jsonl")
    docs = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            obj = json.loads(line)
            docs.append({"id": obj["filename"], "text": obj["text"]})
    return docs


class OnnxEmbedder:
    def __init__(self, model_path, tokenizer_path, max_len=256):
        self.session = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
        self.tokenizer = Tokenizer.from_file(str(tokenizer_path))
        self.tokenizer.enable_truncation(max_length=max_len)
        self.max_len = max_len

    def __call__(self, texts, batch_size=16):
        out = []
        for start in range(0, len(texts), batch_size):
            batch = texts[start:start + batch_size]
            enc = self.tokenizer.encode_batch(batch)
            input_ids = np.zeros((len(batch), self.max_len), dtype=np.int64)
            attention = np.zeros((len(batch), self.max_len), dtype=np.int64)
            for i, e in enumerate(enc):
                ids = e.ids[:self.max_len]
                input_ids[i, :len(ids)] = ids
                attention[i, :len(ids)] = 1
            named = {k.name: v for k, v in zip(
                self.session.get_inputs(),
                [input_ids, attention, np.zeros_like(input_ids)])}
            feeds = {name: named[name] for name in
                     ("input_ids", "attention_mask", "token_type_ids")
                     if name in named}
            logits = self.session.run(None, feeds)[0]
            mask = attention[:, :, None].astype(logits.dtype)
            summed = (logits * mask).sum(1)
            counts = np.clip(mask.sum(1), 1e-9, None)
            emb = summed / counts
            emb = emb / np.clip(np.linalg.norm(emb, axis=1, keepdims=True), 1e-9, None)
            out.append(emb.astype(np.float32))
        return np.concatenate(out)


def evaluate(config_name):
    model_path, tok_path, use_prefix = MODELS[config_name]
    embedder = OnnxEmbedder(model_path, tok_path)
    docs = load_documents()
    seed = json.loads((ROOT / "eval" / "rag_seed.json").read_text(encoding="utf-8"))

    doc_texts = [d["text"] for d in docs]
    if use_prefix:
        doc_texts = ["passage: " + t for t in doc_texts]
    doc_emb = embedder(doc_texts)

    positives = [c for c in seed["cases"] if c.get("sample_type") == "positive"]
    negatives = [c for c in seed["cases"] if c.get("sample_type") == "negative"]

    hits, rr, miss_ids = 0, 0.0, []
    pos_top1_scores = []
    for case in positives:
        query = case["query"]
        if use_prefix:
            query = "query: " + query
        q = embedder([query])[0]
        scores = doc_emb @ q
        top4 = np.argsort(-scores)[:4]
        # 期望是 call 级 slug（如 2026-07-18-call-01），出现在对应分节文本内
        # （"案例编号 2026-07-18-call-01 …"）——与 Java RetrievalBaselineTest 同判定。
        expected_slugs = case.get("expected") or []
        if isinstance(expected_slugs, str):
            expected_slugs = [expected_slugs]
        rank = None
        for r, idx in enumerate(top4, start=1):
            if any(slug in docs[int(idx)]["text"] for slug in expected_slugs):
                rank = r
                break
        pos_top1_scores.append(float(scores.max()))
        if rank:
            hits += 1
            rr += 1.0 / rank
        else:
            miss_ids.append(case["id"])

    neg_top1 = []
    for case in negatives:
        query = case["query"]
        if use_prefix:
            query = "query: " + query
        q = embedder([query])[0]
        neg_top1.append(float((doc_emb @ q).max()))

    n = len(positives)
    return {
        "config": config_name,
        "documents": len(docs),
        "document_hit_at_4": round(hits / n, 4),
        "document_mrr_at_4": round(rr / n, 4),
        "negative_top1_scores": [round(s, 3) for s in neg_top1],
        "missed_ids": miss_ids,
        "positive_top1_scores": [round(x, 3) for x in pos_top1_scores],
    }


if __name__ == "__main__":
    results = [evaluate(name) for name in MODELS]
    print(json.dumps(results, ensure_ascii=False, indent=2))
    Path(r"C:/Users/dk/AppData/Local/Temp/embed-test/shootout.json").write_text(
        json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
