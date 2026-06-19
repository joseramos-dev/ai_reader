from typing import Literal

from pydantic import BaseModel, Field


class ResumenCapitulo(BaseModel):
    numero: int = Field(ge=1)
    nombre: str
    resumen: str


class ResumenesMetadata(BaseModel):
    items: list[ResumenCapitulo] = []
    status: Literal["idle", "loading", "ready", "failed"] = "idle"
    error: str | None = None
    total: int = Field(default=0, ge=0)
    percent: float = Field(default=0.0, ge=0.0, le=100.0)


class LlmChapterSummary(BaseModel):
    """Schema for Ollama structured output."""

    resumen: str
