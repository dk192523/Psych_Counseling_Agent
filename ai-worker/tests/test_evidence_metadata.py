import asyncio
import json
from unittest.mock import AsyncMock, Mock

import pytest
from pydantic import ValidationError

from dk_ai_worker.config import Settings
from dk_ai_worker.models import Candidate, RefineLimits, RefineRequest
from dk_ai_worker.ranking import rank_candidates
from dk_ai_worker.service import IntelligenceService


def _candidate(**overrides):
    return Candidate.model_validate({
        "id": "C1", "slug": "2026-03-08-call-01", "title": "压力与睡眠",
        "text": "工作压力持续影响睡眠", "vectorScore": 0.8, **overrides,
    })


def test_legacy_case_and_metadata_round_trip_through_ranking():
    legacy = _candidate()
    assert legacy.domain == "psych-cases"
    assert (legacy.source_id, legacy.chunk_id, legacy.citation_url) == ("", "", "")
    knowledge = _candidate(
        slug="", domain="psych-knowledge", sourceId="睡眠指南.md", chunkId="chunk-2",
        citationUrl="https://example.test/guide",
    )
    decoded = Candidate.model_validate_json(knowledge.model_dump_json(by_alias=True))
    ranked = rank_candidates("工作压力与睡眠", [decoded], 1)
    assert ranked[0].candidate == knowledge
    assert decoded.model_dump(by_alias=True)["sourceId"] == "睡眠指南.md"


@pytest.mark.parametrize("overrides", [
    {"domain": "psych-knowledge"},
    {"domain": "psych-policy"},
    {"slug": "../private"},
    {"slug": ""},
    {"domain": "unknown"},
    {"unexpected": True},
])
def test_candidate_rejects_non_case_transcript_slug_and_invalid_contract(overrides):
    with pytest.raises(ValidationError):
        _candidate(**overrides)


def test_refine_preserves_metadata_and_never_searches_non_case_transcripts(tmp_path):
    service = IntelligenceService(Settings(
        DEEPSEEK_API_KEY="", AI_WORKER_SHARED_SECRET="test-secret",
        COUNSELING_TRANSCRIPT_DIRECTORY=tmp_path,
    ))
    service._llm = Mock()
    service._llm.complete_json = AsyncMock(return_value=(
        {"selected_ids": ["C1", "C2"], "evidence_gaps": []}, None,
    ))
    service._transcripts = Mock()
    service._transcripts.search.return_value = []
    knowledge = _candidate(slug="", domain="psych-policy", sourceId="policy.md",
                           chunkId="chunk-1", citationUrl="https://example.test/policy")
    case = _candidate(id="C2")
    request = RefineRequest(
        contractVersion="1", requestId="metadata", currentMessage="工作压力影响睡眠",
        candidates=[knowledge, case],
        limits=RefineLimits(maxEvidence=2, snippetsPerCase=1, snippetMaxChars=200),
    )

    response = asyncio.run(service.refine(request))

    service._transcripts.search.assert_called_once()
    assert service._transcripts.search.call_args.args[0] == case.slug
    professional = next(item for item in response.selected_evidence if item.id == "C1")
    assert professional.slug == ""
    assert professional.snippets == []
    prompt = json.loads(service._llm.complete_json.call_args.args[1])
    assert prompt["candidates"][0]["domain"] == "psych-policy"
    assert prompt["candidates"][0]["sourceId"] == "policy.md"
    assert prompt["candidates"][0]["chunkId"] == "chunk-1"
    assert prompt["candidates"][0]["citationUrl"] == "https://example.test/policy"
