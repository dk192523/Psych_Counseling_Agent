from dk_ai_worker.models import GradeDraft, RecallCandidate, RecallLimits, RecallRequest
from dk_ai_worker.service import (
    _extract_safety_lines,
    _heuristic_plan,
    _fit_digest,
    _merge_safety_sections,
    _rrf_episodes,
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
    assert _extract_safety_lines(with_lines) == ["- [user] 我想死"]

    assert _extract_safety_lines("") == []
    assert _extract_safety_lines("没有安全备注段的摘要") == []


def test_merge_safety_sections_unions_batch_first_and_dedupes():
    inherited = ["- [user] 我想死"]
    batch = "## 安全备注\n- [user] 我又有了那个念头\n- [user] 我想死"

    merged = _merge_safety_sections(inherited, batch)

    assert merged.startswith("## 安全备注\n")
    assert merged.count("- [user] 我想死") == 1
    assert "- [user] 我又有了那个念头" in merged
    lines = merged.splitlines()
    assert lines[1] == "- [user] 我又有了那个念头", "batch lines come first"

    assert _merge_safety_sections([], "") == ""
    assert _merge_safety_sections([], None) == ""



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
