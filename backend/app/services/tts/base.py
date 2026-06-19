from __future__ import annotations

from typing import Any, Protocol, runtime_checkable


@runtime_checkable
class TtsEngine(Protocol):
    engine_id: str

    def load(self) -> None: ...

    def unload(self) -> None: ...

    def is_loaded(self) -> bool: ...

    async def prepare_voice(self, voice_id: str, language_hint: str) -> Any: ...

    async def synthesize(
        self,
        text: str,
        voice_id: str,
        language_hint: str,
        speed: float = 1.0,
    ) -> bytes: ...

    def voice_cache_size(self) -> int: ...
