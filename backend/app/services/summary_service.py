"""Chapter summary generation via Ollama."""

from __future__ import annotations

import asyncio
import logging

from sqlalchemy.orm import Session

from app.db import SessionLocal
from app.models.document import Document
from app.schemas.chapter import Capitulo
from app.schemas.summary import LlmChapterSummary, ResumenCapitulo, ResumenesMetadata
from app.services.capitulos_store import get_capitulos
from app.services.ollama_client import (
    OllamaError,
    chat_structured,
    check_ollama_available_sync,
    ollama_setup_message,
)
from app.services.page_text_store import get_processing_status, list_all_ready_paragraphs
from app.services.resumenes_store import get_resumenes, set_resumenes

logger = logging.getLogger(__name__)

_summary_tasks: set[str] = set()
_MAX_CHAPTER_TEXT_CHARS = 12_000


def invalidate_resumenes(session: Session, doc: Document) -> None:
    set_resumenes(doc, ResumenesMetadata())
    session.commit()


def _is_bulk_complete(session: Session, doc: Document) -> bool:
    if not doc.page_count:
        return False
    status = get_processing_status(session, doc.id, doc.page_count)
    return status.is_complete


def _can_start_summaries(session: Session, doc: Document) -> bool:
    capitulos = get_capitulos(doc)
    if capitulos.status != "ready" or not capitulos.items:
        return False
    if not _is_bulk_complete(session, doc):
        return False
    resumenes = get_resumenes(doc)
    if resumenes.status in ("loading", "ready"):
        return False
    return True


def gather_chapter_text(
    pages: list[tuple[int, list[str]]],
    capitulo: Capitulo,
) -> str:
    start = capitulo.pagina
    end = capitulo.pagina + capitulo.longitud - 1
    chunks: list[str] = []
    total = 0
    for page_num, paragraphs in pages:
        if page_num < start or page_num > end:
            continue
        for para in paragraphs:
            text = para.strip()
            if not text:
                continue
            if total + len(text) + 2 > _MAX_CHAPTER_TEXT_CHARS:
                truncated = text[: _MAX_CHAPTER_TEXT_CHARS - total - 3].rstrip() + "..."
                chunks.append(truncated)
                return "\n\n".join(chunks)
            chunks.append(text)
            total += len(text) + 2
    return "\n\n".join(chunks)


async def summarize_chapter(text: str, nombre: str) -> str:
    if not text.strip():
        return "No hay texto disponible para resumir este capítulo."
    prompt = f"""Resume el siguiente capítulo de un libro en español.
El capítulo se titula "{nombre}".

El resumen debe:
- Tener entre 3 y 6 frases
- Capturar las ideas principales y el desarrollo del capítulo
- Estar en español claro y conciso
- No incluir introducciones meta ("Este capítulo trata de...")

Texto del capítulo:
{text[:_MAX_CHAPTER_TEXT_CHARS]}
"""
    system = (
        "Eres un asistente que resume textos literarios y académicos. "
        "Devuelve únicamente JSON válido según el schema."
    )
    result = await chat_structured(prompt, LlmChapterSummary, system=system)
    return result.resumen.strip()


async def _run_summaries(doc_id: str) -> None:
    try:
        with SessionLocal() as session:
            doc = session.get(Document, doc_id)
            if doc is None:
                return
            chapters = get_capitulos(doc).items
            pages = list_all_ready_paragraphs(session, doc_id)

        items: list[ResumenCapitulo] = []
        for chapter in chapters:
            text = gather_chapter_text(pages, chapter)
            resumen = await summarize_chapter(text, chapter.nombre)
            items.append(
                ResumenCapitulo(
                    numero=chapter.numero,
                    nombre=chapter.nombre,
                    resumen=resumen,
                )
            )
            completed = len(items)
            percent = round((completed / len(chapters)) * 100, 1) if chapters else 100.0
            with SessionLocal() as session:
                doc = session.get(Document, doc_id)
                if doc is None:
                    return
                set_resumenes(
                    doc,
                    ResumenesMetadata(
                        items=list(items),
                        status="loading",
                        error=None,
                        total=len(chapters),
                        percent=percent,
                    ),
                )
                session.commit()

        with SessionLocal() as session:
            doc = session.get(Document, doc_id)
            if doc is None:
                return
            set_resumenes(
                doc,
                ResumenesMetadata(
                    items=items,
                    status="ready",
                    error=None,
                    total=len(items),
                    percent=100.0,
                ),
            )
            session.commit()
            logger.info(
                "Chapter summaries ready for doc %s (%d chapters)",
                doc_id,
                len(items),
            )
    except OllamaError as exc:
        logger.warning("Chapter summaries failed for doc %s: %s", doc_id, exc)
        with SessionLocal() as session:
            doc = session.get(Document, doc_id)
            if doc is None:
                return
            prev = get_resumenes(doc)
            set_resumenes(
                doc,
                ResumenesMetadata(
                    items=prev.items,
                    status="failed",
                    error=str(exc),
                ),
            )
            session.commit()
    except Exception as exc:
        logger.warning("Chapter summaries failed for doc %s: %s", doc_id, exc)
        with SessionLocal() as session:
            doc = session.get(Document, doc_id)
            if doc is None:
                return
            prev = get_resumenes(doc)
            set_resumenes(
                doc,
                ResumenesMetadata(
                    items=prev.items,
                    status="failed",
                    error=str(exc),
                ),
            )
            session.commit()
    finally:
        _summary_tasks.discard(doc_id)


def _fail_resumenes_ollama(
    session: Session, doc: Document, chapter_total: int
) -> None:
    message = ollama_setup_message()
    set_resumenes(
        doc,
        ResumenesMetadata(
            items=[],
            status="failed",
            error=message,
            total=chapter_total,
            percent=0.0,
        ),
    )
    session.commit()


def maybe_start_summaries(doc_id: str) -> None:
    if doc_id in _summary_tasks:
        return
    with SessionLocal() as session:
        doc = session.get(Document, doc_id)
        if doc is None:
            return
        if not _can_start_summaries(session, doc):
            return
        chapter_total = len(get_capitulos(doc).items)
        if not check_ollama_available_sync():
            _fail_resumenes_ollama(session, doc, chapter_total)
            return
        set_resumenes(
            doc,
            ResumenesMetadata(
                items=[],
                status="loading",
                error=None,
                total=chapter_total,
                percent=0.0,
            ),
        )
        session.commit()

    _summary_tasks.add(doc_id)
    asyncio.create_task(_run_summaries(doc_id))


def restart_summaries(session: Session, doc: Document) -> ResumenesMetadata:
    """Reset failed/idle summaries and start generation again."""
    capitulos = get_capitulos(doc)
    if capitulos.status != "ready" or not capitulos.items:
        raise ValueError("Los capítulos aún no están listos.")
    if not _is_bulk_complete(session, doc):
        raise ValueError("El texto del documento aún se está procesando.")

    meta = get_resumenes(doc)
    if meta.status == "loading":
        return meta
    if meta.status == "ready":
        return meta

    chapter_total = len(capitulos.items)
    if not check_ollama_available_sync():
        _fail_resumenes_ollama(session, doc, chapter_total)
        raise OllamaError(ollama_setup_message())

    set_resumenes(
        doc,
        ResumenesMetadata(
            items=[],
            status="loading",
            error=None,
            total=chapter_total,
            percent=0.0,
        ),
    )
    session.commit()
    session.refresh(doc)

    doc_id = doc.id
    if doc_id not in _summary_tasks:
        _summary_tasks.add(doc_id)
        asyncio.create_task(_run_summaries(doc_id))

    return get_resumenes(doc)
