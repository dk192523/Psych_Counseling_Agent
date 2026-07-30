import hmac
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
        expected = runtime_settings.shared_secret_value()
        if expected and not token:
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="unauthorized")
        if expected and not hmac.compare_digest(token or "", expected):
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


app = create_app()

