"""Pocket-TTS engine adapter."""

from __future__ import annotations

import asyncio
import io
import logging
import threading
from collections import OrderedDict

import scipy.io.wavfile

from app.config import Settings
from app.services.tts.voices_catalog import CatalogVoice, get_voice

logger = logging.getLogger(__name__)

_MAX_VOICE_CACHE = 5


class PocketTtsEngine:
    engine_id = "pocket_tts"

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._model = None
        self._voice_cache: OrderedDict[str, dict] = OrderedDict()
        self._cache_lock = threading.Lock()
        self._synth_lock = threading.Lock()

    def load(self) -> None:
        from pocket_tts import TTSModel

        logger.info(
            "Loading Pocket-TTS (language=%s, quantize=%s) …",
            self._settings.tts_language,
            self._settings.tts_quantize,
        )
        self._model = TTSModel.load_model(
            language=self._settings.tts_language,
            quantize=self._settings.tts_quantize,
        )
        logger.info("Pocket-TTS loaded (sample_rate=%d Hz)", self._model.sample_rate)

    def unload(self) -> None:
        self._model = None
        with self._cache_lock:
            self._voice_cache.clear()
        logger.info("Pocket-TTS unloaded")

    def is_loaded(self) -> bool:
        return self._model is not None

    def voice_cache_size(self) -> int:
        return len(self._voice_cache)

    def _resolve_synthesis_id(self, voice_id: str) -> str:
        entry = get_voice(voice_id, self.engine_id)
        if entry is not None:
            return entry.resolve_synthesis_id
        return voice_id

    def _resolve_voice_state_sync(self, synthesis_id: str) -> dict:
        with self._cache_lock:
            if synthesis_id in self._voice_cache:
                self._voice_cache.move_to_end(synthesis_id)
                return self._voice_cache[synthesis_id]

            logger.info("Computing Pocket-TTS voice state for '%s' …", synthesis_id)
            state = self._model.get_state_for_audio_prompt(synthesis_id)
            self._voice_cache[synthesis_id] = state
            while len(self._voice_cache) > _MAX_VOICE_CACHE:
                self._voice_cache.popitem(last=False)
            return state

    async def prepare_voice(self, voice_id: str, language_hint: str) -> dict:
        synthesis_id = self._resolve_synthesis_id(voice_id)
        return await asyncio.to_thread(self._resolve_voice_state_sync, synthesis_id)

    def _synthesize_sync(self, text: str, voice_state: dict) -> bytes:
        with self._synth_lock:
            audio = self._model.generate_audio(voice_state, text)
        buf = io.BytesIO()
        scipy.io.wavfile.write(buf, self._model.sample_rate, audio.numpy())
        return buf.getvalue()

    async def synthesize(
        self,
        text: str,
        voice_id: str,
        language_hint: str,
        speed: float = 1.0,
    ) -> bytes:
        del speed  # Pocket-TTS has no native speed; client uses playbackRate.
        voice_state = await self.prepare_voice(voice_id, language_hint)
        return await asyncio.to_thread(self._synthesize_sync, text, voice_state)

    def warm_default_voice(self, voice: CatalogVoice) -> None:
        if not self.is_loaded():
            return
        synthesis_id = voice.resolve_synthesis_id
        try:
            self._resolve_voice_state_sync(synthesis_id)
        except Exception as exc:
            logger.warning("Pocket-TTS warm-up failed for %s: %s", voice.id, exc)
