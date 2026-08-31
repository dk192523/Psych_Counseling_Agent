import hmac
import logging
from contextlib import asynccontextmanager
from typing import Annotated

from fastapi import FastAPI, Header, HTTPException, status

from .config import Settings
from .models import (
    ConsolidateRequest,
    ConsolidateResponse,
    PlanRequest,
    PlanResponse,
    RecallRequest,
    RecallResponse,
    RefineRequest,
    RefineResponse,
)
from .service import IntelligenceService


def create_app(settings: Settings | None = None) -> FastAPI:
    runtime_settings = settings or Settings()
    auth_required = runtime_settings.require_auth()
    configured_secret = runtime_settings.shared_secret_value()
    if auth_required and not configured_secret:
        # 启动即失败，而不是带着零鉴权跑起来：worker 的 recall 决定哪些"用户过往原话"进模型上下文，
        # 无鉴权的 sidecar 等于给任何能连上该端口的进程开了一条提示注入通道。
        raise RuntimeError(
            "AI_WORKER_SHARED_SECRET is required. "
            "Set it, or set AI_WORKER_ALLOW_UNAUTHENTICATED=true to explicitly run without auth."
        )
    if not auth_required:
        logging.getLogger(__name__).warning(
            "AI worker is running WITHOUT authentication "
            "(AI_WORKER_ALLOW_UNAUTHENTICATED=true). Never use this outside local development."
        )

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        service = IntelligenceService(runtime_settings)
        app.state.intelligence = service
        yield
        await service.close()

    app = FastAPI(
        title="DK AI Worker",
        version="0.1.0",
        docs_url=None,
        redoc_url=None,
        lifespan=lifespan,
    )

    def service() -> IntelligenceService:
        return app.state.intelligence

    def authorize(token: str | None) -> None:
        # fail-closed：鉴权是否生效由启动期的 auth_required 决定，绝不由"密钥是否为空"决定。
        # 原实现每个分支都挂在 `if expected` 后面，空密钥时整个函数退化为空操作。
        if not auth_required:
            return
        if not token or not hmac.compare_digest(token, configured_secret):
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="unauthorized")

    def validate_request_id(request_id: str, header_request_id: str | None) -> None:
        if header_request_id and not hmac.compare_digest(request_id, header_request_id):
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="request id mismatch")

    @app.get("/internal/v1/health/live")
    async def live() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/internal/v1/health/ready")
    async def ready() -> dict[str, object]:
        return {
            "status": "ok",
            "heuristicPlanner": True,
            "llmConfigured": service().llm_available,
        }

    @app.post("/internal/v1/plan", response_model=PlanResponse)
    async def plan(
        request: PlanRequest,
        worker_token: Annotated[str | None, Header(alias="X-AI-Worker-Token")] = None,
        header_request_id: Annotated[str | None, Header(alias="X-Request-Id")] = None,
    ) -> PlanResponse:
        authorize(worker_token)
        validate_request_id(request.request_id, header_request_id)
        return await service().plan(request)

    @app.post("/internal/v1/evidence/refine", response_model=RefineResponse)
    async def refine(
        request: RefineRequest,
        worker_token: Annotated[str | None, Header(alias="X-AI-Worker-Token")] = None,
        header_request_id: Annotated[str | None, Header(alias="X-Request-Id")] = None,
    ) -> RefineResponse:
        authorize(worker_token)
        validate_request_id(request.request_id, header_request_id)
        return await service().refine(request)

    @app.post("/internal/v1/memory/consolidate", response_model=ConsolidateResponse)
    async def consolidate(
        request: ConsolidateRequest,
        worker_token: Annotated[str | None, Header(alias="X-AI-Worker-Token")] = None,
        header_request_id: Annotated[str | None, Header(alias="X-Request-Id")] = None,
    ) -> ConsolidateResponse:
        authorize(worker_token)
        validate_request_id(request.request_id, header_request_id)
        return await service().consolidate(request)

    @app.post("/internal/v1/memory/recall", response_model=RecallResponse)
    async def recall(
        request: RecallRequest,
        worker_token: Annotated[str | None, Header(alias="X-AI-Worker-Token")] = None,
        header_request_id: Annotated[str | None, Header(alias="X-Request-Id")] = None,
    ) -> RecallResponse:
        authorize(worker_token)
        validate_request_id(request.request_id, header_request_id)
        return await service().recall(request)

    return app


# 不在模块级构造 app：鉴权配置的 fail-closed 校验在 create_app 内，模块级实例化会把这道校验
# 提前到 import 期，导致"导入本模块"就依赖完整环境变量（测试首当其冲）。
# 入口统一走 uvicorn --factory dk_ai_worker.main:create_app（见 Dockerfile）。

