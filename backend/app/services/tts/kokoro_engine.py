"""Kokoro TTS engine adapter."""

from __future__ import annotations

import asyncio
import io
import logging
import wave
from typing import Any

import numpy as np

from app.services.tts.voices_catalog import kokoro_lang_code

logger = logging.getLogger(__name__)

_SAMPLE_RATE = 24000


class KokoroEngine:
    engine_id = "kokoro"

    def __init__(self) -> None:
        self._pipelines: dict[str, Any] = {}

    def load(self) -> None:
        logger.info("Kokoro engine ready (pipelines load lazily per lang_code)")

    def unload(self) -> None:
        self._pipelines.clear()
        logger.info("Kokoro engine unloaded")

    def is_loaded(self) -> bool:
        return True

    def voice_cache_size(self) -> int:
        return len(self._pipelines)

    def _get_pipeline(self, lang_code: str):
        if lang_code not in self._pipelines:
            from kokoro import KPipeline

            logger.info("Loading Kokoro pipeline lang_code=%s …", lang_code)
            self._pipelines[lang_code] = KPipeline(lang_code=lang_code)
        return self._pipelines[lang_code]

    def _synthesize_sync(
        self,
        text: str,
        voice_id: str,
        language_hint: str,
        speed: float = 1.0,
    ) -> bytes:
        lang_code = kokoro_lang_code(language_hint)
        pipeline = self._get_pipeline(lang_code)
        audio_parts: list[np.ndarray] = []

        for _gs, _ps, audio in pipeline(text, voice=voice_id, speed=speed):
            if audio is None:
                continue
            if hasattr(audio, "numpy"):
                audio_parts.append(audio.numpy())
            else:
                audio_parts.append(np.asarray(audio, dtype=np.float32))

        if not audio_parts:
            raise RuntimeError("Kokoro produced no audio")

        combined = np.concatenate(audio_parts)
        clipped = np.clip(combined, -1.0, 1.0)
        pcm = (clipped * 32767).astype(np.int16)

        buf = io.BytesIO()
        with wave.open(buf, "wb") as wf:
            wf.setnchannels(1)
            wf.setsampwidth(2)
            wf.setframerate(_SAMPLE_RATE)
            wf.writeframes(pcm.tobytes())
        return buf.getvalue()

    async def prepare_voice(self, voice_id: str, language_hint: str) -> None:
        lang_code = kokoro_lang_code(language_hint)
        await asyncio.to_thread(self._get_pipeline, lang_code)

    async def synthesize(
        self,
        text: str,
        voice_id: str,
        language_hint: str,
        speed: float = 1.0,
    ) -> bytes:
        return await asyncio.to_thread(
            self._synthesize_sync, text, voice_id, language_hint, speed
        )

    def warm_default_voice(self, voice_id: str, language_hint: str) -> None:
        try:
            lang_code = kokoro_lang_code(language_hint)
            self._get_pipeline(lang_code)
        except Exception as exc:
            logger.warning("Kokoro warm-up failed: %s", exc)
