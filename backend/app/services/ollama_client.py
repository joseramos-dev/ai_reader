"""Async HTTP client for Ollama structured JSON chat."""

from __future__ import annotations

import logging
from typing import TypeVar

import httpx
from pydantic import BaseModel

from app.config import settings
from app.services.text_normalize import fix_nested_strings

logger = logging.getLogger(__name__)

T = TypeVar("T", bound=BaseModel)


class OllamaError(Exception):
    pass


def ollama_setup_message() -> str:
    return (
        "Ollama no está disponible. Instálalo desde https://ollama.com/download "
        "(Windows), abre la aplicación Ollama y descarga el modelo con: "
        f"ollama pull {settings.ollama_model}"
    )


def check_ollama_available_sync() -> bool:
    url = f"{settings.ollama_base_url.rstrip('/')}/api/tags"
    try:
        with httpx.Client(timeout=5.0) as client:
            response = client.get(url)
            response.raise_for_status()
            return True
    except httpx.HTTPError:
        return False


async def check_ollama_available() -> bool:
    url = f"{settings.ollama_base_url.rstrip('/')}/api/tags"
    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            response = await client.get(url)
            response.raise_for_status()
            return True
    except httpx.HTTPError:
        return False


async def chat_structured(
    prompt: str,
    schema_model: type[T],
    *,
    system: str | None = None,
) -> T:
    url = f"{settings.ollama_base_url.rstrip('/')}/api/chat"
    messages: list[dict[str, str]] = []
    if system:
        messages.append({"role": "system", "content": system})
    messages.append({"role": "user", "content": prompt})

    payload = {
        "model": settings.ollama_model,
        "messages": messages,
        "stream": False,
        "format": schema_model.model_json_schema(),
        "options": {"temperature": 0},
    }

    try:
        async with httpx.AsyncClient(timeout=settings.ollama_timeout_seconds) as client:
            response = await client.post(url, json=payload)
            response.raise_for_status()
            data = response.json()
    except httpx.ConnectError as exc:
        raise OllamaError(ollama_setup_message()) from exc
    except httpx.HTTPError as exc:
        raise OllamaError(f"Error de Ollama: {exc}") from exc

    content = data.get("message", {}).get("content")
    if not content:
        raise OllamaError("Ollama devolvió una respuesta vacía.")

    try:
        parsed = schema_model.model_validate_json(content)
        fixed = fix_nested_strings(parsed.model_dump())
        return schema_model.model_validate(fixed)
    except Exception as exc:
        logger.warning("Invalid Ollama JSON: %s", content[:500])
        raise OllamaError("Ollama devolvió JSON inválido.") from exc
