"""Piper TTS engine adapter."""

from __future__ import annotations

import asyncio
import io
import logging
import subprocess
import sys
import wave
from collections import OrderedDict
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)

_MAX_ONNX_CACHE = 2
_PIPER_DATA_DIR = Path("./data/piper")


class PiperEngine:
    engine_id = "piper"

    def __init__(self) -> None:
        self._voices: OrderedDict[str, Any] = OrderedDict()
        _PIPER_DATA_DIR.mkdir(parents=True, exist_ok=True)

    def load(self) -> None:
        logger.info("Piper engine ready (ONNX models load lazily per voice)")

    def unload(self) -> None:
        self._voices.clear()
        logger.info("Piper engine unloaded")

    def is_loaded(self) -> bool:
        return True

    def voice_cache_size(self) -> int:
        return len(self._voices)

    def _onnx_path(self, voice_id: str) -> Path:
        return _PIPER_DATA_DIR / f"{voice_id}.onnx"

    def _ensure_voice_downloaded(self, voice_id: str) -> Path:
        onnx_path = self._onnx_path(voice_id)
        if onnx_path.exists():
            return onnx_path

        logger.info("Downloading Piper voice %s …", voice_id)
        result = subprocess.run(
            [sys.executable, "-m", "piper.download_voices", voice_id, "--data-dir", str(_PIPER_DATA_DIR)],
            capture_output=True,
            text=True,
            check=False,
        )
        if result.returncode != 0:
            raise RuntimeError(
                f"Failed to download Piper voice '{voice_id}': {result.stderr or result.stdout}"
            )
        if not onnx_path.exists():
            raise FileNotFoundError(f"Piper ONNX not found after download: {onnx_path}")
        return onnx_path

    def _load_voice_sync(self, voice_id: str):
        if voice_id in self._voices:
            self._voices.move_to_end(voice_id)
            return self._voices[voice_id]

        from piper import PiperVoice

        onnx_path = self._ensure_voice_downloaded(voice_id)
        logger.info("Loading Piper ONNX %s …", voice_id)
        voice = PiperVoice.load(str(onnx_path))
        self._voices[voice_id] = voice
        while len(self._voices) > _MAX_ONNX_CACHE:
            self._voices.popitem(last=False)
        return voice

    def _synthesize_sync(
        self, text: str, voice_id: str, speed: float = 1.0
    ) -> bytes:
        from piper.config import SynthesisConfig

        voice = self._load_voice_sync(voice_id)
        length_scale = 1.0 / speed if speed > 0 else 1.0
        syn_config = SynthesisConfig(length_scale=length_scale)
        buf = io.BytesIO()
        with wave.open(buf, "wb") as wav_file:
            voice.synthesize_wav(text, wav_file, syn_config=syn_config)
        return buf.getvalue()

    async def prepare_voice(self, voice_id: str, language_hint: str) -> None:
        await asyncio.to_thread(self._load_voice_sync, voice_id)

    async def synthesize(
        self,
        text: str,
        voice_id: str,
        language_hint: str,
        speed: float = 1.0,
    ) -> bytes:
        return await asyncio.to_thread(self._synthesize_sync, text, voice_id, speed)

    def warm_default_voice(self, voice_id: str) -> None:
        try:
            self._load_voice_sync(voice_id)
        except Exception as exc:
            logger.warning("Piper warm-up failed for %s: %s", voice_id, exc)
