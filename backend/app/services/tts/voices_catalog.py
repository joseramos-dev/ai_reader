"""Curated voice catalogue — single source of truth for GET /voices."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class CatalogVoice:
    id: str
    engine: str
    language_hint: str
    label: str
    is_default: bool = False
    synthesis_id: str | None = None

    @property
    def resolve_synthesis_id(self) -> str:
        return self.synthesis_id or self.id


VOICES: list[CatalogVoice] = [
    # pocket_tts — 4 ES + 1 EN
    CatalogVoice("lola", "pocket_tts", "es", "Lola", is_default=True),
    CatalogVoice(
        "es_carmen",
        "pocket_tts",
        "es",
        "Carmen",
        synthesis_id=(
            "hf://kyutai/pocket-tts-without-voice-cloning/languages/"
            "spanish_24l/embeddings/lola.safetensors@"
            "e041936c75475d350b405bc870bcf7c22da4e9e6"
        ),
    ),
    CatalogVoice(
        "es_pedro",
        "pocket_tts",
        "es",
        "Pedro",
        synthesis_id="hf://kyutai/pocket-tts/g-Vi8PgmSY0-enhanced-v2.wav@64ab7d24c479d736a83b8cc666c4a776fca30fda",
    ),
    CatalogVoice(
        "es_lucia",
        "pocket_tts",
        "es",
        "Lucía",
        synthesis_id="hf://kyutai/pocket-tts/common_voice_es_19762977-enhanced-v2.mp3@64ab7d24c479d736a83b8cc666c4a776fca30fda",
    ),
    CatalogVoice("alba", "pocket_tts", "en", "Alba"),
    # kokoro — 4 ES + 1 EN
    CatalogVoice("ef_dora", "kokoro", "es", "Dora"),
    CatalogVoice("em_alex", "kokoro", "es", "Alex"),
    CatalogVoice("ef_kore", "kokoro", "es", "Kore"),
    CatalogVoice("em_santa", "kokoro", "es", "Santa"),
    CatalogVoice("af_bella", "kokoro", "en", "Bella"),
    # piper — 4 ES + 1 EN
    CatalogVoice("es_ES-carlfm-x_low", "piper", "es", "Carlfm"),
    CatalogVoice("es_ES-davefx-medium", "piper", "es", "Davefx"),
    CatalogVoice("es_ES-mls_10246-low", "piper", "es", "MLS 10246"),
    CatalogVoice("es_ES-sharvard-medium", "piper", "es", "Sharvard"),
    CatalogVoice("en_US-lessac-medium", "piper", "en", "Lessac"),
]

def list_catalog_voices() -> list[CatalogVoice]:
    return list(VOICES)


def get_default_voice() -> CatalogVoice:
    for voice in VOICES:
        if voice.is_default:
            return voice
    return VOICES[0]


def get_voice(voice_id: str, engine: str | None = None) -> CatalogVoice | None:
    for voice in VOICES:
        if voice.id == voice_id and (engine is None or voice.engine == engine):
            return voice
    return None


def is_valid_catalog_voice(voice_id: str, engine: str) -> bool:
    return get_voice(voice_id, engine) is not None


def voices_for_engine(engine: str) -> list[CatalogVoice]:
    return [v for v in VOICES if v.engine == engine]


def kokoro_lang_code(language_hint: str) -> str:
    return "a" if language_hint == "en" else "e"
