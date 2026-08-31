from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field


def _to_camel(value: str) -> str:
    head, *tail = value.split("_")
    return head + "".join(part.capitalize() for part in tail)


class ContractModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=_to_camel,
        populate_by_name=True,
        extra="forbid",
        str_strip_whitespace=True,
    )


class HistoryMessage(ContractModel):
    role: Literal["user", "assistant"]
    content: str = Field(min_length=1, max_length=1_200)


class PlanLimits(ContractModel):
    max_queries: int = Field(ge=1, le=8)
    query_max_chars: int = Field(ge=20, le=500)
    max_missing_information: int = Field(ge=0, le=10)


class PlanRequest(ContractModel):
    contract_version: Literal["1"]
    request_id: str = Field(min_length=1, max_length=100)
    current_message: str = Field(min_length=1, max_length=4_000)
    recent_messages: list[HistoryMessage] = Field(default_factory=list, max_length=30)
    limits: PlanLimits
    long_term_digest: str = Field(default="", max_length=3_000)


class PlanResponse(ContractModel):
    contract_version: Literal["1"] = "1"
    request_id: str
    stage: Literal["clarification", "confirmation", "analysis"]
    should_retrieve: bool
    focus: str
    queries: list[str]
    missing_information: list[str]
    engine: str
    degraded: bool
    degraded_reasons: list[str]
    duration_ms: int = Field(ge=0)
    association_hypotheses: list[Annotated[str, Field(max_length=120)]] = Field(
        default_factory=list, max_length=3
    )
    # 回应策略信号（v1 增量字段，带默认值故向后兼容）：
    # response_mode 告诉回答模型这一轮的对话姿态——listen 只反映与陪伴、零提问；
    # clarify 画像有缺口，至多一个澄清问题；explore 用户已征询，可给内容。
    # next_probe 是"本轮最值得了解的一个方向"，是选题方向不是问题原文，
    # 措辞交给回答模型（它才看得到语气），严禁照抄成一句审问。
    response_mode: Literal["listen", "clarify", "explore"] = "clarify"
    next_probe: Annotated[str, Field(max_length=120)] = ""


class Candidate(ContractModel):
    id: str = Field(pattern=r"^C[1-9][0-9]?$", max_length=3)
    slug: str = Field(pattern=r"^\d{4}-\d{2}-\d{2}-call-\d{2}$")
    title: str = Field(default="", max_length=200)
    text: str = Field(min_length=1, max_length=2_000)
    vector_score: float = Field(ge=-1.0, le=1.0)


class RefineLimits(ContractModel):
    max_evidence: int = Field(ge=1, le=8)
    snippets_per_case: int = Field(ge=1, le=3)
    snippet_max_chars: int = Field(ge=80, le=800)


class RefineRequest(ContractModel):
    contract_version: Literal["1"]
    request_id: str = Field(min_length=1, max_length=100)
    current_message: str = Field(min_length=1, max_length=4_000)
    focus: str = Field(default="", max_length=240)
    queries: list[str] = Field(default_factory=list, max_length=8)
    candidates: list[Candidate] = Field(min_length=1, max_length=20)
    limits: RefineLimits


class EvidenceSnippet(ContractModel):
    start: str = Field(max_length=16)
    end: str = Field(max_length=16)
    text: str
    source_url: str = Field(default="", max_length=600)
    score: float


class SelectedEvidence(ContractModel):
    id: str
    slug: str
    rank_score: float
    rank_signals: list[str]
    snippets: list[EvidenceSnippet]


class RefineResponse(ContractModel):
    contract_version: Literal["1"] = "1"
    request_id: str
    selected_evidence: list[SelectedEvidence]
    evidence_gaps: list[str]
    engine: str
    degraded: bool
    degraded_reasons: list[str]
    duration_ms: int = Field(ge=0)


class MemoryMessage(ContractModel):
    role: Literal["user", "assistant"]
    content: str = Field(min_length=1, max_length=2_000)
    safety_relevant: bool = False


class ConsolidateLimits(ContractModel):
    max_digest_chars: int = Field(ge=200, le=3_000)


class ConsolidateRequest(ContractModel):
    contract_version: Literal["1"]
    request_id: str = Field(min_length=1, max_length=100)
    existing_digest: str = Field(default="", max_length=4_000)
    messages: list[MemoryMessage] = Field(min_length=1, max_length=60)
    limits: ConsolidateLimits


class ConsolidateResponse(ContractModel):
    contract_version: Literal["1"] = "1"
    request_id: str
    # digest 软上限仍是 3000（_DIGEST_HARD_CAP），但安全备注永不被压缩：当批次内多条长危机
    # 消息使安全段本身超过 3000 时，响应必须能承载完整安全内容，故响应字段放宽到 200_000
    # （请求侧 MemoryMessage ≤2000/条 × 60 条的自然上界之内）。Java 侧消费方不做长度校验。
    digest: str = Field(max_length=200_000)
    engine: str
    degraded: bool
    degraded_reasons: list[str]
    duration_ms: int = Field(ge=0)


class RecallCandidate(ContractModel):
    id: int = Field(ge=1)
    role: Literal["user", "assistant"]
    content: str = Field(min_length=1, max_length=2_000)
    recency_score: float = Field(ge=0.0, le=1.0)


class RecallLimits(ContractModel):
    max_episodes: int = Field(ge=1, le=8)
    snippet_max_chars: int = Field(ge=80, le=800)


class RecallRequest(ContractModel):
    contract_version: Literal["1"]
    request_id: str = Field(min_length=1, max_length=100)
    current_message: str = Field(min_length=1, max_length=4_000)
    queries: list[Annotated[str, Field(max_length=300)]] = Field(min_length=1, max_length=8)
    candidates: list[RecallCandidate] = Field(min_length=1, max_length=60)
    limits: RecallLimits


class RecallEpisode(ContractModel):
    id: int
    role: Literal["user", "assistant"]
    snippet: str = Field(min_length=1, max_length=800)
    score: float


class RecallResponse(ContractModel):
    contract_version: Literal["1"] = "1"
    request_id: str
    episodes: list[RecallEpisode]
    engine: str
    degraded: bool
    degraded_reasons: list[str]
    duration_ms: int = Field(ge=0)


class PlanDraft(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    stage: Literal["clarification", "confirmation", "analysis"]
    should_retrieve: bool
    focus: str
    queries: list[str]
    missing_information: list[str]
    association_hypotheses: list[str] = Field(default_factory=list)
    # LLM 可能给出越界值，这里只收原始字符串，白名单归一交给 service 层。
    response_mode: str = "clarify"
    next_probe: str = Field(default="", max_length=200)


class GradeDraft(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    selected_ids: list[str]
    evidence_gaps: list[str]
