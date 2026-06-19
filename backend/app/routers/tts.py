import logging

from fastapi import APIRouter, HTTPException
from fastapi.responses import Response

from app.schemas.tts import EngineLoadRequest, TtsRequest, VoiceInfo
import app.services.tts.registry as registry_module
from app.services.tts.voices_catalog import is_valid_catalog_voice

logger = logging.getLogger(__name__)

router = APIRouter(tags=["tts"])

_WAV_HEADERS = {
    "Content-Disposition": "attachment; filename=speech.wav",
    "Cache-Control": "no-cache",
}


def _require_registry():
    if registry_module.engine_registry is None:
        raise HTTPException(status_code=503, detail="TTS registry is not ready")
    return registry_module.engine_registry


@router.get("/voices", response_model=list[VoiceInfo])
async def list_voices() -> list[VoiceInfo]:
    """Return the curated catalogue of available voices."""
    registry = _require_registry()
    return [
        VoiceInfo(
            id=v.id,
            engine=v.engine,
            language_hint=v.language_hint,
            label=v.label,
            is_default=v.is_default,
        )
        for v in registry.list_voices()
    ]


@router.post("/api/v1/tts/engine/load")
async def load_engine(body: EngineLoadRequest) -> dict:
    """Load a TTS engine (unloads the previous one). Idempotent if already active."""
    registry = _require_registry()
    try:
        await registry.load_engine(body.engine)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        logger.exception("Engine load failed")
        raise HTTPException(status_code=500, detail=str(exc)) from exc

    return {
        "active_engine": registry.active_engine_id,
        "loading": registry.loading,
    }


@router.post("/api/v1/tts")
async def text_to_speech(body: TtsRequest):
    """
    Generate speech from text using the active engine (or specified engine).
    """
    registry = _require_registry()

    engine_id = body.engine or registry.active_engine_id
    if engine_id is None:
        raise HTTPException(status_code=503, detail="No TTS engine is loaded")

    if body.engine and body.engine != registry.active_engine_id:
        raise HTTPException(
            status_code=400,
            detail=f"Engine '{body.engine}' is not active. POST /api/v1/tts/engine/load first.",
        )

    default = registry.list_voices()
    default_voice = next((v for v in default if v.is_default), default[0])
    voice = body.voice or default_voice.id

    if not is_valid_catalog_voice(voice, engine_id):
        raise HTTPException(
            status_code=400,
            detail=f"Invalid voice '{voice}' for engine '{engine_id}'",
        )

    wav_bytes = await registry.synthesize(body.text, voice, engine_id)
    return Response(content=wav_bytes, media_type="audio/wav", headers=_WAV_HEADERS)
