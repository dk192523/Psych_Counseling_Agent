from dk_ai_worker.models import Candidate
from dk_ai_worker.ranking import rank_candidates, tokenize


def test_chinese_tokenizer_keeps_phrase_and_character_ngrams():
    tokens = tokenize("工作压力导致睡眠困难")
    assert "工作压力" in tokens or "工作" in tokens
    assert "睡眠" in tokens
    assert "压力" in tokens


def test_rrf_combines_vector_and_bm25_signals():
    candidates = [
        Candidate(
            id="C1",
            slug="2026-03-08-call-01",
            title="睡眠与工作压力",
            text="工作压力持续影响睡眠和白天注意力",
            vector_score=0.70,
        ),
        Candidate(
            id="C2",
            slug="2026-03-08-call-02",
            title="普通人际冲突",
            text="和朋友发生了一次争执",
            vector_score=0.80,
        ),
    ]
    ranked = rank_candidates("工作压力 睡眠", candidates, 2)
    assert ranked[0].candidate.id == "C1"
    assert "bm25" in ranked[0].signals or "lexical" in ranked[0].signals
