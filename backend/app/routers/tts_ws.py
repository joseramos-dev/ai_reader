import json
import logging

from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from starlette.websockets import WebSocketState

import app.services.tts.registry as registry_module
from app.services.tts_read_session import TtsReadSession

logger = logging.getLogger(__name__)

router = APIRouter(tags=["tts"])


@router.websocket("/api/v1/tts/read")
async def tts_read(websocket: WebSocket) -> None:
    await websocket.accept()

    registry = registry_module.engine_registry
    if registry is None:
        await websocket.send_json(
            {"event": "error", "message": "TTS service is not ready"}
        )
        await websocket.close()
        return

    session = TtsReadSession(websocket, registry)
    try:
        while True:
            message = await websocket.receive()
            msg_type = message.get("type")

            if msg_type == "websocket.disconnect":
                break

            if "text" not in message:
                continue

            try:
                payload = json.loads(message["text"])
            except json.JSONDecodeError:
                await session.handle_message({"action": "__invalid__"})
                continue

            if not isinstance(payload, dict):
                continue

            should_continue = await session.handle_message(payload)
            if not should_continue:
                break
    except WebSocketDisconnect:
        logger.debug("TTS read WebSocket disconnected")
    finally:
        await session.cleanup()
        if websocket.client_state == WebSocketState.CONNECTED:
            try:
                await websocket.close()
            except Exception:
                logger.debug("WebSocket close after session end", exc_info=True)
