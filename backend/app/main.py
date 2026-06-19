import logging
from contextlib import asynccontextmanager

import uvicorn
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.config import settings
from app.db import SessionLocal, init_db
from app.routers import documents, health, tts, tts_ws
from app.services import document_service
import app.services.tts.registry as registry_module
from app.services.tts.registry import EngineRegistry
from app.services.tts.voices_catalog import get_default_voice

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    with SessionLocal() as session:
        document_service.cleanup_expired(session)

    registry = EngineRegistry(settings)
    registry_module.engine_registry = registry

    default_voice = get_default_voice()
    logger.info(
        "Preloading default engine %s (voice %s) …",
        default_voice.engine,
        default_voice.id,
    )
    await registry.load_engine(default_voice.engine)

    logger.info("TTS registry ready (active: %s).", registry.active_engine_id)
    yield
    await registry.unload_active()
    registry_module.engine_registry = None
    logger.info("TTS registry shut down.")


app = FastAPI(
    title="Multi-Engine TTS API",
    description=(
        "REST API for speech synthesis with Pocket-TTS, Kokoro, and Piper."
    ),
    version="0.2.0",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(health.router)
app.include_router(tts.router)
app.include_router(tts_ws.router)
app.include_router(documents.router)


if __name__ == "__main__":
    uvicorn.run("app.main:app", host=settings.host, port=settings.port, reload=False)
