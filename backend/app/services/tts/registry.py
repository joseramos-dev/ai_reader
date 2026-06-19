"""Single active TTS engine registry with global synthesis semaphore."""

from __future__ import annotations

import asyncio
import logging
from typing import Any

from app.config import Settings
from app.services.tts.base import TtsEngine
from app.services.tts.kokoro_engine import KokoroEngine
from app.services.tts.piper_engine import PiperEngine
from app.services.tts.pocket_tts_engine import PocketTtsEngine
from app.services.tts.voices_catalog import (
    CatalogVoice,
    get_default_voice,
    get_voice,
    list_catalog_voices,
    voices_for_engine,
)

logger = logging.getLogger(__name__)

ENGINE_IDS = ("pocket_tts", "kokoro", "piper")
MAX_CONCURRENT_SYNTH = 3


class EngineRegistry:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._engines: dict[str, TtsEngine] = {
            "pocket_tts": PocketTtsEngine(settings),
            "kokoro": KokoroEngine(),
            "piper": PiperEngine(),
        }
        self._active_id: str | None = None
        self._loading = False
        self._semaphore: asyncio.Semaphore | None = None
        self._text_queue_depth = 0
        self._inflight_synth = 0

    @property
    def loading(self) -> bool:
        return self._loading

    @property
    def active_engine_id(self) -> str | None:
        return self._active_id

    @property
    def text_queue_depth(self) -> int:
        return self._text_queue_depth

    def set_text_queue_depth(self, depth: int) -> None:
        self._text_queue_depth = depth

    def _get_semaphore(self) -> asyncio.Semaphore:
        if self._semaphore is None:
            self._semaphore = asyncio.Semaphore(MAX_CONCURRENT_SYNTH)
        return self._semaphore

    def get_active(self) -> TtsEngine:
        if self._active_id is None:
            raise RuntimeError("No TTS engine is loaded")
        return self._engines[self._active_id]

    def engine_status(self) -> dict[str, dict[str, bool]]:
        return {
            engine_id: {"loaded": self._active_id == engine_id and engine.is_loaded()}
            for engine_id, engine in self._engines.items()
        }

    def memory_info(self) -> dict[str, Any]:
        active = self.get_active() if self._active_id else None
        cuda_available = False
        try:
            import torch

            cuda_available = torch.cuda.is_available()
        except ImportError:
            pass

        return {
            "active_engine_loaded": active.is_loaded() if active else False,
            "cuda_available": cuda_available,
            "text_queue_depth": self._text_queue_depth,
            "voice_cache_size": active.voice_cache_size() if active else 0,
        }

    async def load_engine(self, engine_id: str) -> None:
        if engine_id not in self._engines:
            raise ValueError(f"Unknown engine '{engine_id}'")

        if self._active_id == engine_id and self._engines[engine_id].is_loaded():
            return

        self._loading = True
        try:
            if self._active_id is not None and self._active_id != engine_id:
                await self.unload_active()

            engine = self._engines[engine_id]
            if not engine.is_loaded():
                await asyncio.to_thread(engine.load)

            self._active_id = engine_id
            self._warm_default_for_engine(engine_id)
            logger.info("Active TTS engine: %s", engine_id)
        finally:
            self._loading = False

    async def unload_active(self) -> None:
        if self._active_id is None:
            return
        for _ in range(600):
            if self._inflight_synth == 0 and self._text_queue_depth == 0:
                break
            await asyncio.sleep(0.05)
        engine = self._engines[self._active_id]
        await asyncio.to_thread(engine.unload)
        self._active_id = None

    def _warm_default_for_engine(self, engine_id: str) -> None:
        default = get_default_voice()
        candidates = voices_for_engine(engine_id)
        warm_voice = next((v for v in candidates if v.is_default), candidates[0] if candidates else None)
        if warm_voice is None:
            return

        engine = self._engines[engine_id]
        if engine_id == "pocket_tts" and hasattr(engine, "warm_default_voice"):
            engine.warm_default_voice(warm_voice)
        elif engine_id == "kokoro" and hasattr(engine, "warm_default_voice"):
            engine.warm_default_voice(warm_voice.id, warm_voice.language_hint)
        elif engine_id == "piper" and hasattr(engine, "warm_default_voice"):
            engine.warm_default_voice(warm_voice.id)

    async def prepare_voice(self, voice_id: str, engine_id: str) -> Any:
        entry = get_voice(voice_id, engine_id)
        if entry is None:
            raise ValueError(f"Unknown voice '{voice_id}' for engine '{engine_id}'")
        if self._active_id != engine_id:
            raise RuntimeError(
                f"Engine '{engine_id}' is not active (active: {self._active_id})"
            )
        return await self.get_active().prepare_voice(voice_id, entry.language_hint)

    async def synthesize(
        self,
        text: str,
        voice_id: str,
        engine_id: str,
        speed: float = 1.0,
    ) -> bytes:
        entry = get_voice(voice_id, engine_id)
        if entry is None:
            raise ValueError(f"Unknown voice '{voice_id}' for engine '{engine_id}'")
        if self._active_id != engine_id:
            raise RuntimeError(
                f"Engine '{engine_id}' is not active (active: {self._active_id})"
            )

        async with self._get_semaphore():
            self._inflight_synth += 1
            try:
                return await self.get_active().synthesize(
                    text, voice_id, entry.language_hint, speed=speed
                )
            finally:
                self._inflight_synth -= 1

    def list_voices(self) -> list[CatalogVoice]:
        return list_catalog_voices()


engine_registry: EngineRegistry | None = None
