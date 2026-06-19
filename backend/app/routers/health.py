from fastapi import APIRouter

from app.schemas.tts import EngineMemoryInfo, HealthResponse
import app.services.tts.registry as registry_module

router = APIRouter(tags=["health"])


@router.get("/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    engine_registry = registry_module.engine_registry
    if engine_registry is None:
        return HealthResponse(
            status="starting",
            active_engine=None,
            loading=True,
            engines={},
            memory=EngineMemoryInfo(
                active_engine_loaded=False,
                cuda_available=False,
                text_queue_depth=0,
                voice_cache_size=0,
            ),
        )

    return HealthResponse(
        status="ok",
        active_engine=engine_registry.active_engine_id,
        loading=engine_registry.loading,
        engines=engine_registry.engine_status(),
        memory=EngineMemoryInfo(**engine_registry.memory_info()),
    )
