from typing import Literal

from pydantic import BaseModel, Field


class PageTextMetadata(BaseModel):
    page: int = Field(ge=1)
    paragraphs: list[str] = []
    status: Literal["idle", "loading", "ready", "failed"] = "idle"
    error: str | None = None


class PageTextProcessingStatus(BaseModel):
    total: int = Field(ge=0)
    ready: int = Field(ge=0)
    loading: int = Field(ge=0)
    failed: int = Field(ge=0)
    idle: int = Field(ge=0)
    percent: float = Field(ge=0, le=100)
    is_complete: bool = False


class LlmPageTextExtraction(BaseModel):
    """Schema for Ollama structured output."""

    paragraphs: list[str]
