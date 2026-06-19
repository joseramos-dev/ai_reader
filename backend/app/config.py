from pathlib import Path

from pydantic import model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

_BACKEND_DIR = Path(__file__).resolve().parent.parent


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8")

    # TTS model
    tts_language: str = "spanish_24l"
    tts_default_voice: str = "lola"
    tts_quantize: bool = True

    # Server
    host: str = "0.0.0.0"
    port: int = 8000
    cors_origins: list[str] = ["*"]

    # Documents (relative paths resolve under backend/)
    upload_dir: str = "./uploads"
    db_url: str = "sqlite:///./data/documents.db"
    document_ttl_days: int = 5
    page_chunk_size: int = 10

    # Ollama (chapter detection)
    ollama_base_url: str = "http://127.0.0.1:11434"
    ollama_model: str = "qwen3:8b"
    ollama_timeout_seconds: float = 120.0
    chapters_recent_limit: int = 10

    @model_validator(mode="after")
    def _resolve_storage_paths(self) -> "Settings":
        upload = Path(self.upload_dir)
        if not upload.is_absolute():
            self.upload_dir = str((_BACKEND_DIR / upload).resolve())

        if self.db_url.startswith("sqlite:///./"):
            rel = self.db_url.removeprefix("sqlite:///./")
            db_path = (_BACKEND_DIR / rel).resolve()
            self.db_url = f"sqlite:///{db_path.as_posix()}"
        return self


settings = Settings()
