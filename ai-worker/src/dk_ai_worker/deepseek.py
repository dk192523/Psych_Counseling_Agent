import asyncio
import json
import logging
from typing import Any

import httpx

from .config import Settings

logger = logging.getLogger(__name__)


class DeepSeekJsonClient:
    """Small async client that exposes JSON only and never logs conversation text."""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._semaphore = asyncio.Semaphore(settings.model_max_concurrency)
        self._client = httpx.AsyncClient(
            timeout=httpx.Timeout(settings.deepseek_timeout_seconds),
            limits=httpx.Limits(
                max_connections=settings.model_max_concurrency,
                max_keepalive_connections=settings.model_max_concurrency,
            ),
        )

    @property
    def available(self) -> bool:
        return self._settings.llm_enabled and bool(self._settings.api_key_value())

    async def complete_json(
        self, system_prompt: str, user_prompt: str
    ) -> tuple[dict[str, Any] | None, str | None]:
        if not self.available:
            return None, "llm_unavailable"

        endpoint = self._settings.deepseek_base_url.rstrip("/") + "/chat/completions"
        payload = {
            "model": self._settings.deepseek_chat_model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            "response_format": {"type": "json_object"},
            "temperature": 0.1,
            "stream": False,
        }
        headers = {
            "Authorization": f"Bearer {self._settings.api_key_value()}",
            "Accept": "application/json",
            "Content-Type": "application/json; charset=UTF-8",
        }
        try:
            async with self._semaphore:
                response = await self._client.post(endpoint, headers=headers, json=payload)
            response.raise_for_status()
            body = response.json()
            content = body["choices"][0]["message"]["content"]
            return _decode_json_object(content), None
        except httpx.HTTPStatusError as error:
            logger.warning("DeepSeek JSON call failed; status=%s", error.response.status_code)
            return None, f"llm_http_{error.response.status_code}"
        except (httpx.HTTPError, KeyError, IndexError, TypeError, ValueError) as error:
            logger.warning("DeepSeek JSON call failed; errorType=%s", type(error).__name__)
            return None, "llm_invalid_response"

    async def aclose(self) -> None:
        await self._client.aclose()


def _decode_json_object(content: Any) -> dict[str, Any]:
    if not isinstance(content, str):
        raise TypeError("model content must be a string")
    text = content.strip()
    if text.startswith("```"):
        first_newline = text.find("\n")
        text = text[first_newline + 1 :] if first_newline >= 0 else text[3:]
        if text.endswith("```"):
            text = text[:-3]
    start = text.find("{")
    end = text.rfind("}")
    if start < 0 or end < start:
        raise ValueError("model response does not contain a JSON object")
    decoded = json.loads(text[start : end + 1])
    if not isinstance(decoded, dict):
        raise TypeError("model response must be a JSON object")
    return decoded
