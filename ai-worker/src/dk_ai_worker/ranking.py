import json
import logging
import math
import re
import threading
import unicodedata
from collections import OrderedDict
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import parse_qsl, urlencode, urlparse, urlunparse

import jieba
from rank_bm25 import BM25Okapi

from .models import Candidate, EvidenceSnippet

jieba.setLogLevel(logging.ERROR)

_HAN_SEQUENCE = re.compile(r"[\u3400-\u9fff]+")
_ASCII_TOKEN = re.compile(r"[a-z0-9][a-z0-9._+-]*")
_SLUG = re.compile(r"^\d{4}-\d{2}-\d{2}-call-\d{2}$")
_STOP_WORDS = {
    "一个",
    "这个",
    "那个",
    "就是",
    "然后",
    "自己",
    "我们",
    "你们",
    "他们",
    "什么",
    "怎么",
    "问题",
    "觉得",
    "因为",
    "所以",
    "可以",
    "需要",
    "还是",
    "现在",
    "没有",
    "不是",
    "可能",
    "事情",
    "时候",
    "已经",
    "用户",
    "案例",
}


def tokenize(text: str, limit: int = 600, deduplicate: bool = False) -> list[str]:
    normalized = unicodedata.normalize("NFKC", text or "").lower()
    tokens: list[str] = []
    seen: set[str] = set()

    def add(token: str) -> None:
        token = token.strip()
        if (
            token
            and token not in _STOP_WORDS
            and (not deduplicate or token not in seen)
            and len(tokens) < limit
        ):
            if deduplicate:
                seen.add(token)
            tokens.append(token)

    for part in jieba.lcut(normalized, cut_all=False):
        for han in _HAN_SEQUENCE.findall(part):
            if len(han) >= 2:
                add(han)
        for ascii_token in _ASCII_TOKEN.findall(part):
            if len(ascii_token) >= 2:
                add(ascii_token)

    # Character n-grams make sparse, conversational Chinese more robust than
    # relying on one dictionary segmentation alone.
    for sequence in _HAN_SEQUENCE.findall(normalized):
        for size in (2, 3):
            for start in range(max(0, len(sequence) - size + 1)):
                add(sequence[start : start + size])
                if len(tokens) >= limit:
                    return tokens
    return tokens


@dataclass(frozen=True)
class RankedCandidate:
    candidate: Candidate
    score: float
    signals: list[str]


def rank_candidates(
    query: str,
    candidates: list[Candidate],
    limit: int,
    llm_selected_ids: list[str] | None = None,
) -> list[RankedCandidate]:
    if not candidates or limit <= 0:
        return []

    by_id = {candidate.id.upper(): candidate for candidate in candidates}
    eligible_ids = list(by_id)
    llm_rank: dict[str, int] = {}
    if llm_selected_ids is not None:
        deduplicated = []
        for value in llm_selected_ids:
            candidate_id = value.strip().upper()
            if candidate_id in by_id and candidate_id not in deduplicated:
                deduplicated.append(candidate_id)
        eligible_ids = deduplicated
        llm_rank = {candidate_id: index + 1 for index, candidate_id in enumerate(deduplicated)}
        if not eligible_ids:
            return []

    vector_order = sorted(candidates, key=lambda item: (-item.vector_score, item.id))
    vector_rank = {item.id.upper(): index + 1 for index, item in enumerate(vector_order)}

    query_tokens = tokenize(query, deduplicate=True)
    corpus = [tokenize(f"{candidate.title} {candidate.text}") for candidate in candidates]
    bm25_scores = [0.0] * len(candidates)
    if query_tokens and any(corpus):
        bm25_scores = [float(value) for value in BM25Okapi(corpus).get_scores(query_tokens)]
    bm25_order = sorted(
        range(len(candidates)), key=lambda index: (-bm25_scores[index], candidates[index].id)
    )
    bm25_rank = {candidates[index].id.upper(): rank + 1 for rank, index in enumerate(bm25_order)}
    bm25_by_id = {
        candidates[index].id.upper(): bm25_scores[index] for index in range(len(candidates))
    }
    query_set = set(query_tokens)
    lexical_overlap = {
        candidate.id.upper(): len(query_set.intersection(corpus[index]))
        for index, candidate in enumerate(candidates)
    }
    lexical_order = sorted(
        candidates,
        key=lambda item: (-lexical_overlap[item.id.upper()], item.id),
    )
    lexical_rank = {
        item.id.upper(): index + 1
        for index, item in enumerate(lexical_order)
        if lexical_overlap[item.id.upper()] > 0
    }

    ranked: list[RankedCandidate] = []
    for candidate_id in eligible_ids:
        score = 1.0 / (60 + vector_rank[candidate_id])
        signals = ["vector"]
        if bm25_by_id[candidate_id] > 0:
            score += 1.0 / (60 + bm25_rank[candidate_id])
            signals.append("bm25")
        elif candidate_id in lexical_rank:
            # With only one or two candidates BM25's IDF can legitimately be
            # zero for every term; retain the lexical signal instead of losing
            # an obviously matching case.
            score += 1.0 / (60 + lexical_rank[candidate_id])
            signals.append("lexical")
        if candidate_id in llm_rank:
            score += 1.0 / (60 + llm_rank[candidate_id])
            signals.append("llm")
        ranked.append(
            RankedCandidate(
                candidate=by_id[candidate_id],
                score=round(score * 1_000, 6),
                signals=signals,
            )
        )
    ranked.sort(key=lambda item: (-item.score, item.candidate.id))
    return ranked[:limit]


class TranscriptRepository:
    def __init__(self, directory: Path, cache_size: int = 128) -> None:
        self._directory = directory.expanduser().resolve()
        self._cache_size = cache_size
        self._cache: OrderedDict[str, dict] = OrderedDict()
        self._cache_lock = threading.Lock()

    def search(
        self, slug: str, query: str, snippet_count: int, max_chars: int
    ) -> list[EvidenceSnippet]:
        transcript = self._load(slug)
        if transcript is None:
            return []
        cues = transcript.get("cues")
        if not isinstance(cues, list) or not cues:
            return []
        query_tokens = tokenize(query, deduplicate=True)
        if not query_tokens:
            return []

        windows: list[tuple[int, int, str, list[str]]] = []
        for index in range(len(cues)):
            start_index = max(0, index - 3)
            end_index = min(len(cues) - 1, index + 5)
            text = " ".join(
                str(cue.get("text", "")).strip()
                for cue in cues[start_index : end_index + 1]
                if isinstance(cue, dict) and str(cue.get("text", "")).strip()
            )
            windows.append((start_index, end_index, text, tokenize(text)))

        corpus = [window[3] for window in windows]
        bm25_scores = [0.0] * len(windows)
        if any(corpus):
            bm25_scores = [float(value) for value in BM25Okapi(corpus).get_scores(query_tokens)]
        query_set = set(query_tokens)
        candidates: list[tuple[float, int, int, str]] = []
        for index, (start_index, end_index, text, window_tokens) in enumerate(windows):
            overlap = len(query_set.intersection(window_tokens))
            score = max(0.0, bm25_scores[index]) + math.log1p(overlap)
            if overlap > 0 or bm25_scores[index] > 0:
                candidates.append((score, start_index, end_index, text))
        candidates.sort(key=lambda item: (-item[0], item[1]))

        selected: list[tuple[float, int, int, str]] = []
        for candidate in candidates:
            _, start_index, end_index, _ = candidate
            if any(start_index <= existing[2] + 2 and end_index >= existing[1] - 2 for existing in selected):
                continue
            selected.append(candidate)
            if len(selected) >= snippet_count:
                break

        video_url = transcript.get("url") if isinstance(transcript.get("url"), str) else ""
        snippets: list[EvidenceSnippet] = []
        for score, start_index, end_index, text in selected:
            start = _cue_time(cues[start_index], "start")
            end = _cue_time(cues[end_index], "end")
            snippets.append(
                EvidenceSnippet(
                    start=start,
                    end=end,
                    text=_truncate(text, max_chars),
                    source_url=_timestamp_url(video_url, start),
                    score=round(score, 6),
                )
            )
        return snippets

    def _load(self, slug: str) -> dict | None:
        if not _SLUG.fullmatch(slug):
            return None
        with self._cache_lock:
            cached = self._cache.get(slug)
            if cached is not None:
                self._cache.move_to_end(slug)
                return cached

        path = (self._directory / f"{slug}.json").resolve()
        if path.parent != self._directory or not path.is_file():
            return None
        try:
            transcript = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError):
            return None
        if not isinstance(transcript, dict) or transcript.get("slug") != slug:
            return None
        with self._cache_lock:
            self._cache[slug] = transcript
            self._cache.move_to_end(slug)
            while len(self._cache) > self._cache_size:
                self._cache.popitem(last=False)
        return transcript


def _cue_time(cue: object, key: str) -> str:
    if not isinstance(cue, dict):
        return ""
    return str(cue.get(key, ""))[:16]


def _truncate(text: str, max_chars: int) -> str:
    if len(text) <= max_chars:
        return text
    return text[: max(1, max_chars - 1)] + "…"


def _timestamp_url(url: str, start: str) -> str:
    parsed = urlparse(url)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        return ""
    parts = start.split(":")
    try:
        seconds = int(parts[0]) * 3_600 + int(parts[1]) * 60 + int(float(parts[2]))
    except (ValueError, IndexError):
        seconds = 0
    query = dict(parse_qsl(parsed.query, keep_blank_values=True))
    query["t"] = str(seconds)
    return urlunparse(parsed._replace(query=urlencode(query)))
