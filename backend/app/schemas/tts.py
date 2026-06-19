from pydantic import BaseModel, field_validator


class TtsRequest(BaseModel):
    text: str
    voice: str | None = None
    engine: str | None = None
    stream: bool = True

    @field_validator("text")
    @classmethod
    def text_must_not_be_empty(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("text cannot be empty")
        return v


class VoiceInfo(BaseModel):
    id: str
    engine: str
    language_hint: str
    label: str
    is_default: bool


class EngineLoadRequest(BaseModel):
    engine: str


class EngineMemoryInfo(BaseModel):
    active_engine_loaded: bool
    cuda_available: bool
    text_queue_depth: int
    voice_cache_size: int


class HealthResponse(BaseModel):
    status: str
    active_engine: str | None
    loading: bool
    engines: dict[str, dict[str, bool]]
    memory: EngineMemoryInfo
