"""WebSocket TTS read session — parallel synthesis workers, ordered delivery."""

from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass
from typing import Any

from fastapi import WebSocket
from starlette.websockets import WebSocketDisconnect

from app.services.tts.registry import EngineRegistry
from app.services.tts.voices_catalog import is_valid_catalog_voice

logger = logging.getLogger(__name__)

TEXT_QUEUE_MAXSIZE = 9
SYNTH_WORKERS = 3
MIN_SPEED = 0.75
MAX_SPEED = 1.5


def _clamp_speed(speed: float) -> float:
    return max(MIN_SPEED, min(MAX_SPEED, speed))


@dataclass(frozen=True)
class PhraseItem:
    seq: int
    generation: int
    text: str


class TtsReadSession:
    def __init__(self, websocket: WebSocket, registry: EngineRegistry) -> None:
        self._websocket = websocket
        self._registry = registry
        self._phrase_queue: asyncio.Queue[PhraseItem | None] | None = asyncio.Queue(
            maxsize=TEXT_QUEUE_MAXSIZE
        )
        self._completed: dict[int, tuple[int, bytes]] = {}
        self._completed_cond = asyncio.Condition()
        self._voice_id: str | None = None
        self._engine_id: str | None = None
        self._speed = 1.0
        self._stopped = False
        self._generation = 0
        self._next_seq = 0
        self._next_send_seq = 0
        self._audio_index = 0
        self._synth_in_flight = 0
        self._worker_tasks: list[asyncio.Task[None]] = []
        self._sender_task: asyncio.Task[None] | None = None
        self._session_start: float | None = None
        self._first_audio_sent = False
        self._send_lock = asyncio.Lock()

    async def handle_message(self, message: dict[str, Any]) -> bool:
        """Handle one client message. Returns False when the session should end."""
        action = message.get("action")

        if action == "start":
            engine = message.get("engine")
            voice = message.get("voice")
            if not engine or not voice:
                await self._send_error("start requires 'engine' and 'voice'")
                return True

            if not is_valid_catalog_voice(voice, engine):
                await self._send_error(f"Invalid voice '{voice}' for engine '{engine}'")
                return True

            if engine != self._registry.active_engine_id:
                await self._send_error(
                    f"Engine '{engine}' is not active "
                    f"(active: {self._registry.active_engine_id})"
                )
                return True

            self._engine_id = engine
            self._voice_id = voice
            self._speed = _clamp_speed(float(message.get("speed", 1.0)))
            self._session_start = time.monotonic()
            self._first_audio_sent = False

            try:
                await self._registry.prepare_voice(voice, engine)
            except Exception as exc:
                await self._send_error(str(exc))
                return True

            self._ensure_workers()
            await self._send({"event": "ready"})
            return True

        if action == "set_speed":
            self._speed = _clamp_speed(float(message.get("speed", 1.0)))
            await self._send({"event": "speed_updated", "speed": self._speed})
            return True

        if action == "enqueue":
            if self._stopped or self._phrase_queue is None:
                return True
            phrases = message.get("phrases") or []
            for phrase in phrases:
                text = str(phrase).strip()
                if not text:
                    continue
                seq = self._next_seq
                self._next_seq += 1
                item = PhraseItem(seq=seq, generation=self._generation, text=text)
                await self._phrase_queue.put(item)
            await self._emit_pipeline_depth()
            return True

        if action == "clear":
            self._clear_queue()
            await self._emit_pipeline_depth()
            await self._send({"event": "cleared", "generation": self._generation})
            return True

        if action == "stop":
            await self.cleanup()
            return False

        await self._send_error(f"Unknown action '{action}'")
        return True

    def _ensure_workers(self) -> None:
        if self._worker_tasks:
            return
        for worker_id in range(SYNTH_WORKERS):
            self._worker_tasks.append(
                asyncio.create_task(self._synth_worker(worker_id))
            )
        self._sender_task = asyncio.create_task(self._sender())

    def _clear_queue(self) -> None:
        self._generation += 1
        self._next_seq = 0
        self._next_send_seq = 0
        self._audio_index = 0
        if self._phrase_queue is None:
            return

        while True:
            try:
                self._phrase_queue.get_nowait()
            except asyncio.QueueEmpty:
                break

        self._completed.clear()
        self._update_queue_depth()

    async def _emit_pipeline_depth(self) -> None:
        if self._stopped:
            return
        text_depth = self._phrase_queue.qsize() if self._phrase_queue else 0
        pending_send = sum(
            1 for seq, _ in self._completed.items() if seq >= self._next_send_seq
        )
        try:
            await self._send(
                {
                    "event": "pipeline_depth",
                    "text_depth": text_depth,
                    "synth_in_flight": self._synth_in_flight,
                    "pending_send": pending_send,
                }
            )
        except (WebSocketDisconnect, RuntimeError, asyncio.CancelledError):
            self._stopped = True
        except Exception:
            self._stopped = True

    def _update_queue_depth(self) -> None:
        if self._phrase_queue is None:
            self._registry.set_text_queue_depth(0)
            return
        self._registry.set_text_queue_depth(self._phrase_queue.qsize())

    async def _maybe_emit_queue_empty(self) -> None:
        if self._stopped or self._phrase_queue is None:
            return
        pending_send = any(
            seq >= self._next_send_seq for seq in self._completed
        )
        if (
            self._phrase_queue.empty()
            and self._synth_in_flight == 0
            and not pending_send
            and self._voice_id is not None
        ):
            try:
                await self._send({"event": "queue_empty"})
            except (WebSocketDisconnect, RuntimeError, asyncio.CancelledError):
                self._stopped = True
            except Exception:
                self._stopped = True

    async def _synth_worker(self, worker_id: int) -> None:
        del worker_id
        while not self._stopped:
            if (
                self._phrase_queue is None
                or self._voice_id is None
                or self._engine_id is None
            ):
                await asyncio.sleep(0.1)
                continue

            try:
                item = await asyncio.wait_for(self._phrase_queue.get(), timeout=0.5)
            except asyncio.TimeoutError:
                await self._maybe_emit_queue_empty()
                continue

            if item is None:
                continue

            self._update_queue_depth()
            await self._emit_pipeline_depth()

            generation = item.generation
            seq = item.seq
            self._synth_in_flight += 1
            await self._emit_pipeline_depth()
            synth_start = time.monotonic()

            try:
                speed = self._speed
                if self._stopped:
                    continue
                wav = await self._registry.synthesize(
                    item.text,
                    self._voice_id,
                    self._engine_id,
                    speed=speed,
                )
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                logger.warning("Phrase synthesis failed: %s", exc)
                if not self._stopped:
                    await self._send_error(str(exc))
            else:
                if not self._stopped and generation == self._generation:
                    async with self._completed_cond:
                        self._completed[seq] = (generation, wav)
                        self._completed_cond.notify_all()
                    chunk_ms = (time.monotonic() - synth_start) * 1000
                    logger.debug("Synth chunk seq %d: %.0f ms", seq, chunk_ms)
            finally:
                self._synth_in_flight -= 1
                if not self._stopped:
                    await self._emit_pipeline_depth()
                    await self._maybe_emit_queue_empty()

    async def _sender(self) -> None:
        while not self._stopped:
            if self._voice_id is None:
                await asyncio.sleep(0.1)
                continue

            async with self._completed_cond:
                while (
                    not self._stopped
                    and self._next_send_seq in self._completed
                ):
                    generation, wav = self._completed.pop(self._next_send_seq)
                    seq = self._next_send_seq
                    self._next_send_seq += 1

                    if generation != self._generation:
                        continue

                    if not self._first_audio_sent and self._session_start is not None:
                        ttfa_ms = (time.monotonic() - self._session_start) * 1000
                        logger.debug("TTFA: %.0f ms (seq %d)", ttfa_ms, seq)
                        self._first_audio_sent = True

                    index = self._audio_index
                    self._audio_index += 1

                    try:
                        await self._send(
                            {
                                "event": "audio_start",
                                "index": index,
                                "generation": generation,
                            }
                        )
                        await self._send_bytes(wav)
                        await self._send(
                            {
                                "event": "audio_end",
                                "index": index,
                                "generation": generation,
                            }
                        )
                    except (WebSocketDisconnect, RuntimeError, asyncio.CancelledError):
                        return
                    except Exception:
                        return

                    await self._emit_pipeline_depth()
                    await self._maybe_emit_queue_empty()

                if self._stopped:
                    break

                try:
                    await asyncio.wait_for(self._completed_cond.wait(), timeout=0.5)
                except asyncio.TimeoutError:
                    await self._maybe_emit_queue_empty()

    async def cleanup(self) -> None:
        if self._stopped:
            return
        self._stopped = True
        self._generation += 1
        self._clear_queue()
        self._registry.set_text_queue_depth(0)

        all_tasks = [*self._worker_tasks]
        if self._sender_task is not None:
            all_tasks.append(self._sender_task)

        for task in all_tasks:
            task.cancel()

        for task in all_tasks:
            try:
                await asyncio.wait_for(task, timeout=2.0)
            except (asyncio.CancelledError, asyncio.TimeoutError):
                pass

        self._worker_tasks.clear()
        self._sender_task = None
        self._phrase_queue = None
        self._voice_id = None
        self._engine_id = None

    async def _send(self, payload: dict[str, Any]) -> None:
        if self._stopped:
            return
        async with self._send_lock:
            if self._stopped:
                return
            try:
                await self._websocket.send_json(payload)
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                logger.debug("WebSocket JSON send failed: %s", exc)
                self._stopped = True
                return

    async def _send_bytes(self, data: bytes) -> None:
        if self._stopped:
            return
        async with self._send_lock:
            if self._stopped:
                return
            try:
                await self._websocket.send_bytes(data)
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                logger.debug("WebSocket bytes send failed: %s", exc)
                self._stopped = True
                return

    async def _send_error(self, message: str) -> None:
        try:
            await self._send({"event": "error", "message": message})
        except Exception:
            pass
