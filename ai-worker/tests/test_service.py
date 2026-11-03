import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock

from dk_ai_worker.models import (
    ConsolidateLimits,
    ConsolidateRequest,
    GradeDraft,
    MemoryMessage,
    RecallCandidate,
    RecallLimits,
    RecallRequest,
)
from dk_ai_worker.service import (
    IntelligenceService,
    _decode_safety_content,
    _encode_safety_record,
    _extract_safety_lines,
    _heuristic_consolidation,
    _heuristic_plan,
    _fit_digest,
    _merge_safety_sections,
    _rrf_episodes,
    _safety_section,
    _valid_grade_selection,
    _validate_grade,
)


def test_validate_grade_accepts_a_valid_llm_contract():
    grade = _validate_grade({"selected_ids": ["C1"], "evidence_gaps": []})

    assert grade == GradeDraft(selected_ids=["C1"], evidence_gaps=[])
    assert _valid_grade_selection(grade, {"C1", "C2"}, limit=2)


def test_grade_selection_rejects_unknown_duplicate_or_excess_ids():
    assert not _valid_grade_selection(
        GradeDraft(selected_ids=["C3"], evidence_gaps=[]), {"C1", "C2"}, limit=2
    )
    assert not _valid_grade_selection(
        GradeDraft(selected_ids=["C1", "c1"], evidence_gaps=[]), {"C1", "C2"}, limit=2
    )
    assert not _valid_grade_selection(
        GradeDraft(selected_ids=["C1", "C2"], evidence_gaps=[]), {"C1", "C2"}, limit=1
    )


def test_rrf_episodes_never_fill_with_zero_hit_candidates():
    request = RecallRequest(
        contractVersion="1",
        requestId="req-1",
        currentMessage="工作压力让我睡不着",
        queries=["工作压力 睡眠"],
        candidates=[
            RecallCandidate(id=1, role="user", content="最近工作压力很大", recencyScore=0.1),
            RecallCandidate(id=2, role="assistant", content="今天吃了苹果", recencyScore=1.0),
        ],
        limits=RecallLimits(maxEpisodes=4, snippetMaxChars=300),
    )

    episodes = _rrf_episodes(request, {1: 1 / 61, 2: 0.0})

    assert [episode.id for episode in episodes] == [1]


def test_fit_digest_rescues_body_when_safety_section_blows_soft_budget():
    safety = "## 安全备注\n- [user] " + "我不想活了，" + "倾诉内容" * 300
    assert len(safety) > 1200
    body = "## 人物关系链\n用户与母亲关系紧张。"

    digest = _fit_digest(body, safety, 1200)

    assert "## 人物关系链" in digest, "body portrait must survive the safety-blown soft budget"
    assert "我不想活了，" in digest
    assert len(digest) <= 3000


def test_fit_digest_never_truncates_safety_section_beyond_hard_cap():
    safety = "## 安全备注\n- [user] " + "很长的危机叙述" * 600
    assert len(safety) > 3000

    digest = _fit_digest("## 已确认事实\n暂无", safety, 1200)

    assert digest == safety, "safety content must never be compressed, even beyond the hard cap"


def test_extract_safety_lines_drops_placeholder_and_keeps_verbatim_lines():
    placeholder = "## 已确认事实\n无\n\n## 安全备注\n无\n\n## 待确认问题\n暂无"
    assert _extract_safety_lines(placeholder) == []

    with_lines = "## 安全备注\n- [user] 我想死\n\n## 待确认问题\n暂无"
    assert _extract_safety_lines(with_lines) == ["- [user] [safety:v1] 我想死"]

    assert _extract_safety_lines("") == []
    assert _extract_safety_lines("没有安全备注段的摘要") == []


def test_merge_safety_sections_unions_batch_first_and_dedupes():
    inherited = ["- [user] 我想死"]
    batch = "## 安全备注\n- [user] 我又有了那个念头\n- [user] 我想死"

    merged = _merge_safety_sections(inherited, batch)

    assert merged.startswith("## 安全备注\n")
    assert merged.count("- [user] [safety:v1] 我想死") == 1
    assert "- [user] [safety:v1] 我又有了那个念头" in merged
    lines = merged.splitlines()
    assert lines[1] == "- [user] [safety:v1] 我又有了那个念头", "batch lines come first"

    assert _merge_safety_sections([], "") == ""
    assert _merge_safety_sections([], None) == ""


def test_multiline_safety_matches_java_v1_fixture_and_round_trips():
    # Keep this literal and expected record identical to DefaultCounselingMemoryAgentTest.
    content = "首行\\n\r\n\r\n## 正文标题\n- [user] 正文列表\n- 列表\r尾部\\"
    record = "- [user] [safety:v1] 首行\\\\n\\r\\n\\r\\n## 正文标题\\n- [user] 正文列表\\n- 列表\\r尾部\\\\"
    section = _safety_section([MemoryMessage(role="user", content=content, safety_relevant=True)])

    assert section == "## 安全备注\n" + record
    assert _decode_safety_content(record.removeprefix("- [user] [safety:v1] ")) == content
    inherited = _extract_safety_lines(section)
    assert inherited == [record]
    assert _merge_safety_sections(inherited, section) == section
    assert _merge_safety_sections(_extract_safety_lines(section), "") == section


def test_legacy_multiline_migration_preserves_crlf_blank_lines_and_markdown():
    legacy = (
        "## 安全备注\r\n- [user] 旧记录\\path\r\n\r\n## 正文标题\r\n- 普通列表\r\n"
        "- [assistant] 另一条旧记录\r\n\r\n## 待确认问题\r\n仍需核实"
    )
    content = "旧记录\\path\r\n\r\n## 正文标题\r\n- 普通列表"
    records = _extract_safety_lines(legacy)
    assert records == [
        _encode_safety_record("user", content),
        "- [assistant] [safety:v1] 另一条旧记录",
    ]
    merged = _merge_safety_sections(records, _safety_section([
        MemoryMessage(role="user", content=content, safety_relevant=True)
    ]))
    assert _extract_safety_lines(merged) == records


def _consolidate_request(existing: str, messages: list[MemoryMessage], max_chars: int = 1200) -> ConsolidateRequest:
    return ConsolidateRequest(
        contractVersion="1", requestId="req-safety", existingDigest=existing,
        messages=messages, limits=ConsolidateLimits(maxDigestChars=max_chars),
    )


def test_llm_consolidation_keeps_v1_safety_across_later_batches():
    service = IntelligenceService.__new__(IntelligenceService)
    service._settings = SimpleNamespace(deepseek_chat_model="test-model")
    service._llm = SimpleNamespace(complete_json=AsyncMock(return_value=(
        {"digest": "## 已确认事实\n暂无\n\n## 安全备注\n模型改写\n\n## 待确认问题\n暂无"}, None
    )))
    content = "  开头\r\n\r\n## 已确认事实\n- [user] 正文\n结尾\\  "
    message = MemoryMessage(role="assistant", content=content, safety_relevant=True)
    first = asyncio.run(service.consolidate(_consolidate_request("", [
        message
    ])))
    later = asyncio.run(service.consolidate(_consolidate_request(first.digest, [
        MemoryMessage(role="user", content="普通近况", safety_relevant=False)
    ])))

    assert not first.degraded
    assert first.digest == later.digest
    assert _extract_safety_lines(later.digest) == [_encode_safety_record("assistant", message.content)]
    assert "模型改写" not in later.digest
    assert "## 待确认问题\n暂无" in later.digest


def test_heuristic_consolidation_preserves_safety_beyond_cap_and_in_later_batches():
    first_content = "危机记录\r\n\n## 正文标题\n- [user] 列表\\" + "甲" * 1700
    second_content = "另一个危机\r\n尾部\\" + "乙" * 1700
    first = _heuristic_consolidation(_consolidate_request("## 已确认事实\n旧事实", [
        MemoryMessage(role="user", content=first_content, safety_relevant=True),
        MemoryMessage(role="assistant", content=second_content, safety_relevant=True),
    ], 200))
    assert len(first) > 3000
    expected = [
        _encode_safety_record("user", first_content),
        _encode_safety_record("assistant", second_content),
    ]
    assert _extract_safety_lines(first) == expected
    later = _heuristic_consolidation(_consolidate_request(first, [
        MemoryMessage(role="user", content="普通近况", safety_relevant=False)
    ], 200))
    assert _extract_safety_lines(later) == expected



def _plan_request(current_message: str) -> "PlanRequest":
    from dk_ai_worker.models import PlanLimits, PlanRequest

    return PlanRequest(
        contractVersion="1",
        requestId="req-rhythm",
        currentMessage=current_message,
        recentMessages=[],
        limits=PlanLimits(maxQueries=3, queryMaxChars=180, maxMissingInformation=5),
        longTermDigest="",
    )


def test_heuristic_plan_marks_venting_as_listen_with_probe():
    # 用户在宣泄：listen（只反映与陪伴、零提问），选题方向取第一个待确认事实。
    draft = _heuristic_plan(
        _plan_request("我真的撑不住了，每天加班到半夜，醒来就开始想工作的事，感觉整个人被掏空了。")
    )

    assert draft.response_mode == "listen"
    assert draft.next_probe == "事情发生的具体经过"


def test_heuristic_plan_marks_consent_as_explore_without_probe():
    # 用户已同意梳理：explore，且不再给澄清选题方向。
    draft = _heuristic_plan(_plan_request("好，请帮我完整梳理一下。"))

    assert draft.stage == "analysis"
    assert draft.response_mode == "explore"
    assert draft.next_probe == ""


def test_heuristic_plan_defaults_to_clarify_for_normal_disclosure():
    draft = _heuristic_plan(
        _plan_request("我最近因为工作压力睡不好，想先把事情说清楚。")
    )

    assert draft.response_mode == "clarify"
    assert draft.next_probe


def test_normalize_response_mode_falls_back_to_clarify():
    from dk_ai_worker.service import _normalize_response_mode

    assert _normalize_response_mode("listen") == "listen"
    assert _normalize_response_mode("explore") == "explore"
    # LLM 幻觉出的越界值 → 最中性的 clarify
    assert _normalize_response_mode("aggressive") == "clarify"
    assert _normalize_response_mode("") == "clarify"


def test_distress_markers_stay_in_sync_with_java_side():
    """A3 单一事实源契约：两侧词表必须一致，改动任一侧必须同步另一侧。

    语义差异（刻意为之，两侧都不要加"活不下去"）：Java `SafetyTerms.DISTRESS_TERMS`
    把"活不下去"归 PASSIVE（意念级、分级更重，触发安全姿态）；worker `_DISTRESS_MARKERS`
    只驱动 deep 规划的 listen 姿态，不需要意念级区分。
    """
    java_distress_terms = {
        "撑不住", "崩溃", "被掏空", "熬不住", "受不了", "绝望",
        "好累", "太累", "喘不过气", "撑不下去", "想哭",
    }
    from dk_ai_worker.service import _DISTRESS_MARKERS

    assert set(_DISTRESS_MARKERS) == java_distress_terms
