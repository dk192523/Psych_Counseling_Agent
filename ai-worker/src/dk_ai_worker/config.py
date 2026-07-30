from pathlib import Path

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    deepseek_api_key: SecretStr | None = Field(default=None, alias="DEEPSEEK_API_KEY")
    deepseek_base_url: str = Field(default="https://api.deepseek.com", alias="DEEPSEEK_BASE_URL")
    deepseek_chat_model: str = Field(default="deepseek-v4-flash", alias="DEEPSEEK_CHAT_MODEL")
    deepseek_timeout_seconds: float = Field(
        default=20.0, ge=1.0, le=120.0, alias="AI_WORKER_MODEL_TIMEOUT_SECONDS"
    )
    model_max_concurrency: int = Field(
        default=4, ge=1, le=64, alias="AI_WORKER_MODEL_MAX_CONCURRENCY"
    )
    llm_enabled: bool = Field(default=True, alias="AI_WORKER_LLM_ENABLED")
    shared_secret: SecretStr | None = Field(default=None, alias="AI_WORKER_SHARED_SECRET")
    transcript_directory: Path = Field(
        default=Path("../counseling-kb/raw"), alias="COUNSELING_TRANSCRIPT_DIRECTORY"
    )

    def api_key_value(self) -> str:
        return self.deepseek_api_key.get_secret_value().strip() if self.deepseek_api_key else ""

    def shared_secret_value(self) -> str:
        return self.shared_secret.get_secret_value() if self.shared_secret else ""

