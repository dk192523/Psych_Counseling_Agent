from dk_ai_worker.models import GradeDraft
from dk_ai_worker.service import (
    _extract_safety_lines,
    _fit_digest,
    _merge_safety_sections,
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

