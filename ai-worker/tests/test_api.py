import json

from fastapi.testclient import TestClient

from dk_ai_worker.config import Settings
from dk_ai_worker.deepseek import DeepSeekJsonClient
from dk_ai_worker.main import create_app


def _settings(tmp_path):
    return Settings(
        DEEPSEEK_API_KEY="",
        AI_WORKER_SHARED_SECRET="test-secret",
        COUNSELING_TRANSCRIPT_DIRECTORY=tmp_path,
    )


def _plan_payload():
    return {
        "contractVersion": "1",
        "requestId": "req-utf8",
        "currentMessage": "我最近因为工作压力睡不好，想先把事情说清楚。",
        "recentMessages": [],
        "limits": {"maxQueries": 3, "queryMaxChars": 180, "maxMissingInformation": 5},
        "longTermDigest": "用户此前提到工作压力影响睡眠。",
    }


def _consolidate_payload():
    return {
        "contractVersion": "1",
        "requestId": "req-memory",
        "existingDigest": "",
        "messages": [
            {
                "role": "user",
                "content": "我爸最近总是半夜才回家，我妈为此和他吵了很多次。",
                "safetyRelevant": False,
            },
            {
                "role": "assistant",
                "content": "听起来家里的紧张气氛让你很难受，你夹在中间。",
                "safetyRelevant": False,
            },
            {"role": "user", "content": "我昨晚想到了伤害自己的念头。", "safetyRelevant": True},
        ],
        "limits": {"maxDigestChars": 1200},
    }


def _recall_payload():
    return {
        "contractVersion": "1",
        "requestId": "req-recall",
        "currentMessage": "我又想起和父亲吵架的事了",
        "queries": ["父亲 吵架"],
        "candidates": [
            {
                "id": 11,
                "role": "user",
                "content": "我和父亲因为选专业的事大吵了一架",
                "recencyScore": 0.9,
            },
            {
                "id": 12,
                "role": "assistant",
                "content": "你当时希望他能先听你说完",
                "recencyScore": 0.5,
            },
            {
                "id": 13,
                "role": "user",
                "content": "最近睡眠很差，白天没有精神",
                "recencyScore": 1.0,
            },
        ],
        "limits": {"maxEpisodes": 2, "snippetMaxChars": 200},
    }


def test_plan_uses_utf8_heuristic_when_llm_is_not_configured(tmp_path):
    with TestClient(create_app(_settings(tmp_path))) as client:
        response = client.post(
            "/internal/v1/plan",
            json=_plan_payload(),
            headers={"X-AI-Worker-Token": "test-secret", "X-Request-Id": "req-utf8"},
        )

    assert response.status_code == 200
    body = response.json()
    assert body["requestId"] == "req-utf8"
    assert body["degraded"] is True
    assert body["queries"]
    assert "工作压力" in body["focus"] or "睡" in body["focus"]


def test_worker_auth_and_request_id_are_checked(tmp_path):
    with TestClient(create_app(_settings(tmp_path))) as client:
        unauthorized = client.post("/internal/v1/plan", json=_plan_payload())
        mismatch = client.post(
            "/internal/v1/plan",
            json=_plan_payload(),
            headers={"X-AI-Worker-Token": "test-secret", "X-Request-Id": "other"},
        )

    assert unauthorized.status_code == 401
    assert mismatch.status_code == 400


def test_refine_reads_only_a_valid_slug_and_returns_timestamped_snippet(tmp_path):
    slug = "2026-03-08-call-01"
    (tmp_path / f"{slug}.json").write_text(
        json.dumps(
            {
                "slug": slug,
                "title": "压力与睡眠",
                "url": "https://example.test/video/1",
                "cues": [
                    {"start": "00:00:01", "end": "00:00:02", "text": "最近工作压力很大"},
                    {"start": "00:00:02", "end": "00:00:04", "text": "晚上经常睡不着"},
                    {"start": "00:00:04", "end": "00:00:06", "text": "白天注意力也受到影响"},
                ],
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    payload = {
        "contractVersion": "1",
        "requestId": "req-refine",
        "currentMessage": "工作压力让我睡不好",
        "focus": "睡眠与现实影响",
        "queries": ["工作压力 睡眠"],
        "candidates": [
            {
                "id": "C1",
                "slug": slug,
                "title": "压力与睡眠",
                "text": "工作压力导致睡眠受影响的案例摘要",
                "vectorScore": 0.82,
            }
        ],
        "limits": {"maxEvidence": 4, "snippetsPerCase": 2, "snippetMaxChars": 420},
    }

    with TestClient(create_app(_settings(tmp_path))) as client:
        response = client.post(
            "/internal/v1/evidence/refine",
            json=payload,
            headers={"X-AI-Worker-Token": "test-secret", "X-Request-Id": "req-refine"},
        )

    assert response.status_code == 200
    body = response.json()
    assert body["selectedEvidence"][0]["id"] == "C1"
    assert body["selectedEvidence"][0]["snippets"]
    assert body["selectedEvidence"][0]["snippets"][0]["sourceUrl"].startswith(
        "https://example.test/video/1"
    )


def test_extra_contract_fields_are_rejected(tmp_path):
    payload = _plan_payload()
    payload["unexpected"] = "拒绝"
    with TestClient(create_app(_settings(tmp_path))) as client:
        response = client.post(
            "/internal/v1/plan",
            json=payload,
            headers={"X-AI-Worker-Token": "test-secret", "X-Request-Id": "req-utf8"},
        )
    assert response.status_code == 422


def test_plan_response_includes_association_hypotheses(tmp_path):
    with TestClient(create_app(_settings(tmp_path))) as client:
        response = client.post(
            "/internal/v1/plan",
            json=_plan_payload(),
            headers={"X-AI-Worker-Token": "test-secret", "X-Request-Id": "req-utf8"},
        )

    assert response.status_code == 200
    assert response.json()["associationHypotheses"] == []


def test_memory_endpoints_require_auth_and_request_id(tmp_path):
    headers = {"X-AI-Worker-Token": "test-secret", "X-Request-Id": "other"}
    with TestClient(create_app(_settings(tmp_path))) as client:
        consolidate_unauthorized = client.post(
            "/internal/v1/memory/consolidate", json=_consolidate_payload()
        )
        recall_unauthorized = client.post("/internal/v1/memory/recall", json=_recall_payload())
        consolidate_mismatch = client.post(
            "/internal/v1/memory/consolidate", json=_consolidate_payload(), headers=headers
        )
        recall_mismatch = client.post(
            "/internal/v1/memory/recall", json=_recall_payload(), headers=headers
        )

    assert consolidate_unauthorized.status_code == 401
    assert recall_unauthorized.status_code == 401
    assert consolidate_mismatch.status_code == 400
    assert recall_mismatch.status_code == 400


def test_memory_extra_contract_fields_are_rejected(tmp_path):
    consolidate = _consolidate_payload()
    consolidate["unexpected"] = "拒绝"
    recall = _recall_payload()
    recall["unexpected"] = "拒绝"
    headers = {"X-AI-Worker-Token": "test-secret"}
    with TestClient(create_app(_settings(tmp_path))) as client:
        consolidate_response = client.post(
            "/internal/v1/memory/consolidate", json=consolidate, headers=headers
        )
        recall_response = client.post("/internal/v1/memory/recall", json=recall, headers=headers)

    assert consolidate_response.status_code == 422
    assert recall_response.status_code == 422


def test_consolidate_degrades_without_key_and_keeps_safety_verbatim(tmp_path):
    with TestClient(create_app(_settings(tmp_path))) as client:
        response = client.post(
            "/internal/v1/memory/consolidate",
            json=_consolidate_payload(),
            headers={"X-AI-Worker-Token": "test-secret", "X-Request-Id": "req-memory"},
        )

    assert response.status_code == 200
    body = response.json()
    assert body["degraded"] is True
    assert body["degradedReasons"] == ["llm_unavailable"]
    assert body["engine"] == "heuristic"
    assert body["digest"]
    assert "## 安全备注" in body["digest"]
    assert "我昨晚想到了伤害自己的念头。" in body["digest"]


def test_consolidate_heuristic_respects_digest_char_cap(tmp_path):
    payload = _consolidate_payload()
    payload["existingDigest"] = "既有摘要内容" * 120
    payload["limits"] = {"maxDigestChars": 250}
    with TestClient(create_app(_settings(tmp_path))) as client:
        response = client.post(
            "/internal/v1/memory/consolidate",
            json=payload,
            headers={"X-AI-Worker-Token": "test-secret", "X-Request-Id": "req-memory"},
        )

    assert response.status_code == 200
    body = response.json()
    assert body["degraded"] is True
    assert 0 < len(body["digest"]) <= 250


def test_consolidate_llm_output_gets_verbatim_safety_section(tmp_path, monkeypatch):
    async def _fake_complete_json(self, system_prompt, user_prompt):
        return {
            "digest": "## 人物关系链\n父亲、母亲\n\n## 安全备注\n（模型改写过的安全内容）"
        }, None

    monkeypatch.setattr(DeepSeekJsonClient, "complete_json", _fake_complete_json)
    with TestClient(create_app(_settings(tmp_path))) as client:
        response = client.post(
            "/internal/v1/memory/consolidate",
            json=_consolidate_payload(),
            headers={"X-AI-Worker-Token": "test-secret", "X-Request-Id": "req-memory"},
        )

    assert response.status_code == 200
    body = response.json()
    assert body["degraded"] is False
    assert "（模型改写过的安全内容）" not in body["digest"]
    assert "- [user] 我昨晚想到了伤害自己的念头。" in body["digest"]
    assert len(body["digest"]) <= 1200


def test_consolidate_carries_forward_inherited_safety_notes(tmp_path, monkeypatch):
    async def _fake_complete_json(self, system_prompt, user_prompt):
        return {"digest": "## 已确认事实\n用户学业压力持续。"}, None

    monkeypatch.setattr(DeepSeekJsonClient, "complete_json", _fake_complete_json)
    payload = _consolidate_payload()
    # 既有摘要里已沉淀历史危机记录；本批没有新的 safetyRelevant 消息。
    payload["existingDigest"] = (
        "## 已确认事实\n用户曾提及危机。\n\n"
        "## 安全备注\n- [user] 我上周说过想死\n\n"
        "## 待确认问题\n暂无"
    )
    payload["messages"] = [
        {"role": "user", "content": "最近睡眠好一些了。", "safetyRelevant": False},
    ]
    with TestClient(create_app(_settings(tmp_path))) as client:
        response = client.post(
            "/internal/v1/memory/consolidate",
            json=payload,
            headers={"X-AI-Worker-Token": "test-secret", "X-Request-Id": "req-memory"},
        )

    assert response.status_code == 200
    body = response.json()
    assert body["degraded"] is False
    assert "- [user] 我上周说过想死" in body["digest"], (
        "inherited safety notes must survive a batch without new crisis messages"
    )


def test_recall_ranks_by_bm25_rrf_without_degradation(tmp_path):
    with TestClient(create_app(_settings(tmp_path))) as client:
        response = client.post(
            "/internal/v1/memory/recall",
            json=_recall_payload(),
            headers={"X-AI-Worker-Token": "test-secret", "X-Request-Id": "req-recall"},
        )

    assert response.status_code == 200
    body = response.json()
    assert body["degraded"] is False
    assert body["engine"] == "bm25+rrf"
    assert [episode["id"] for episode in body["episodes"]][0] == 11
    assert len(body["episodes"]) <= 2
    assert all(len(episode["snippet"]) <= 200 for episode in body["episodes"])


def test_recall_degrades_when_tokenizer_yields_nothing(tmp_path, monkeypatch):
    monkeypatch.setattr("dk_ai_worker.service.tokenize", lambda *args, **kwargs: [])
    with TestClient(create_app(_settings(tmp_path))) as client:
        response = client.post(
            "/internal/v1/memory/recall",
            json=_recall_payload(),
            headers={"X-AI-Worker-Token": "test-secret", "X-Request-Id": "req-recall"},
        )

    assert response.status_code == 200
    body = response.json()
    assert body["degraded"] is True
    assert body["degradedReasons"] == ["no_bm25_hits"]
    assert body["engine"] == "keyword"
    assert [episode["id"] for episode in body["episodes"]] == [13, 11]
