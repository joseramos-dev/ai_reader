from typing import Literal

from pydantic import BaseModel, Field


class Capitulo(BaseModel):
    numero: int = Field(ge=1)
    nombre: str
    pagina: int = Field(ge=1)
    longitud: int = Field(ge=1)


class CapitulosMetadata(BaseModel):
    items: list[Capitulo] = []
    status: Literal["idle", "loading", "ready", "failed"] = "idle"
    source: Literal["outline", "llm", "text_scan", "text_scan_llm"] | None = None
    error: str | None = None


class CapituloInput(BaseModel):
    nombre: str
    pagina: int = Field(ge=1)


class CapitulosOutlineUpdate(BaseModel):
    items: list[CapituloInput]


class LlmChapterExtraction(BaseModel):
    """Schema for Ollama structured output."""

    items: list[CapituloInput]
