"""Chapter detection: outline persistence, index scan, LLM via Ollama."""

from __future__ import annotations

import asyncio
import logging
import re

from pypdf import PdfReader
from sqlalchemy.orm import Session

from app.config import settings
from app.db import SessionLocal
from app.models.document import Document
from app.schemas.chapter import (
    Capitulo,
    CapituloInput,
    CapitulosMetadata,
    LlmChapterExtraction,
)
from app.services import storage_service
from app.services.capitulos_store import get_capitulos, set_capitulos
from app.services.ollama_client import OllamaError, chat_structured
from app.services.page_text_store import list_all_ready_paragraphs
from app.services.summary_service import invalidate_resumenes, maybe_start_summaries

logger = logging.getLogger(__name__)

_INDEX_KEYWORDS = re.compile(
    r"\b(índice|indice|index|contents|table of contents|sumario|contenido)\b",
    re.IGNORECASE,
)

_detection_tasks: set[str] = set()
_text_scan_tasks: set[str] = set()

_CHAPTER_HEADING_PATTERNS = (
    re.compile(
        r"^(?:cap[ií]tulo|chapter|parte|libro|secci[oó]n|book|part)\s+[\w\dIVXLCDM\.\-]+",
        re.IGNORECASE | re.UNICODE,
    ),
    re.compile(r"^[IVXLCDM]+\.\s+\S", re.UNICODE),
    re.compile(r"^\d+\.\s+[A-ZÁÉÍÓÚÑ]", re.UNICODE),
)

_MAX_LLM_PAGE_SUMMARY_CHARS = 12_000
_PAGE_SUMMARY_SNIPPET = 200


def compute_longitudes(
    items: list[CapituloInput], page_count: int | None
) -> list[Capitulo]:
    if not items:
        return []
    sorted_items = sorted(items, key=lambda x: x.pagina)
    result: list[Capitulo] = []
    max_page = page_count or sorted_items[-1].pagina
    for i, item in enumerate(sorted_items):
        if i + 1 < len(sorted_items):
            length = max(1, sorted_items[i + 1].pagina - item.pagina)
        else:
            length = max(1, max_page - item.pagina + 1)
        result.append(
            Capitulo(
                numero=i + 1,
                nombre=item.nombre.strip(),
                pagina=item.pagina,
                longitud=length,
            )
        )
    return result


def _clamp_page(page: int, page_count: int | None) -> int:
    if page_count is None:
        return max(1, page)
    return max(1, min(page, page_count))


def save_outline_chapters(
    session: Session, doc: Document, inputs: list[CapituloInput]
) -> CapitulosMetadata:
    clamped = [
        CapituloInput(nombre=x.nombre, pagina=_clamp_page(x.pagina, doc.page_count))
        for x in inputs
        if x.nombre.strip()
    ]
    items = compute_longitudes(clamped, doc.page_count)
    meta = CapitulosMetadata(
        items=items,
        status="ready",
        source="outline",
        error=None,
    )
    set_capitulos(doc, meta)
    invalidate_resumenes(session, doc)
    session.commit()
    session.refresh(doc)
    maybe_start_summaries(doc.id)
    return meta


def _find_index_page_range(reader: PdfReader, max_scan: int = 30) -> list[int]:
    """Return 1-based page numbers likely containing the table of contents."""
    n = min(len(reader.pages), max_scan)
    hits: list[int] = []
    for i in range(n):
        try:
            text = reader.pages[i].extract_text() or ""
        except Exception:
            continue
        if _INDEX_KEYWORDS.search(text):
            hits.append(i + 1)
    if not hits:
        return list(range(1, min(6, n + 1)))
    pages: set[int] = set()
    for p in hits:
        for offset in range(-1, 4):
            candidate = p + offset
            if 1 <= candidate <= len(reader.pages):
                pages.add(candidate)
    return sorted(pages)


def _extract_index_text(storage_key: str) -> tuple[str, int | None]:
    path = storage_service.full_path(storage_key)
    reader = PdfReader(str(path))
    page_count = len(reader.pages)
    index_pages = _find_index_page_range(reader)
    chunks: list[str] = []
    for page_num in index_pages:
        try:
            text = reader.pages[page_num - 1].extract_text() or ""
        except Exception:
            continue
        if text.strip():
            chunks.append(f"--- Página {page_num} ---\n{text.strip()}")
    return "\n\n".join(chunks), page_count


async def detect_chapters_from_index(
    storage_key: str, page_count: int | None
) -> list[CapituloInput]:
    index_text, detected_count = _extract_index_text(storage_key)
    total = page_count or detected_count
    if not index_text.strip():
        raise OllamaError("No se encontró texto de índice en el PDF.")

    prompt = f"""Analiza el siguiente texto extraído del índice de un libro PDF ({total} páginas en total).

Extrae la lista de capítulos o secciones principales con su número de página de inicio.
Ignora prefacio, agradecimientos, anexos y entradas duplicadas.
Los números de página deben estar entre 1 y {total}.

Texto del índice:
{index_text[:12000]}
"""

    system = (
        "Eres un asistente que extrae metadatos de libros. "
        "Devuelve únicamente JSON válido según el schema."
    )
    result = await chat_structured(
        prompt,
        LlmChapterExtraction,
        system=system,
    )
    if not result.items:
        raise OllamaError("El LLM no encontró capítulos en el índice.")
    return result.items


async def _run_detection(doc_id: str) -> None:
    try:
        with SessionLocal() as session:
            doc = session.get(Document, doc_id)
            if doc is None:
                return
            storage_key = doc.storage_key
            page_count = doc.page_count

        inputs = await detect_chapters_from_index(storage_key, page_count)

        with SessionLocal() as session:
            doc = session.get(Document, doc_id)
            if doc is None:
                return
            clamped = [
                CapituloInput(
                    nombre=x.nombre,
                    pagina=_clamp_page(x.pagina, doc.page_count),
                )
                for x in inputs
                if x.nombre.strip()
            ]
            items = compute_longitudes(clamped, doc.page_count)
            set_capitulos(
                doc,
                CapitulosMetadata(
                    items=items,
                    status="ready",
                    source="llm",
                    error=None,
                ),
            )
            session.commit()
            logger.info("Chapter detection ready for doc %s (%d chapters)", doc_id, len(items))
            maybe_start_summaries(doc_id)
    except Exception as exc:
        logger.warning("Chapter detection failed for doc %s: %s", doc_id, exc)
        with SessionLocal() as session:
            doc = session.get(Document, doc_id)
            if doc is None:
                return
            prev = get_capitulos(doc)
            set_capitulos(
                doc,
                CapitulosMetadata(
                    items=[],
                    status="failed",
                    source="llm",
                    error=str(exc),
                ),
            )
            session.commit()
    finally:
        _detection_tasks.discard(doc_id)


def start_llm_detection(session: Session, doc: Document) -> CapitulosMetadata:
    meta = get_capitulos(doc)
    if meta.status in ("loading", "ready"):
        return meta

    meta = CapitulosMetadata(
        items=[],
        status="loading",
        source="llm",
        error=None,
    )
    set_capitulos(doc, meta)
    session.commit()
    session.refresh(doc)

    doc_id = doc.id
    if doc_id not in _detection_tasks:
        _detection_tasks.add(doc_id)
        asyncio.create_task(_run_detection(doc_id))

    return meta


def _is_all_caps_heading(text: str) -> bool:
    stripped = text.strip()
    if not stripped or len(stripped) > 80:
        return False
    letters = [c for c in stripped if c.isalpha()]
    if len(letters) < 3:
        return False
    return all(c.isupper() for c in letters)


def _matches_chapter_heading(text: str) -> bool:
    first_line = text.strip().split("\n", 1)[0].strip()
    if not first_line:
        return False
    if any(p.search(first_line) for p in _CHAPTER_HEADING_PATTERNS):
        return True
    return _is_all_caps_heading(first_line)


def _normalize_chapter_title(text: str) -> str:
    title = text.strip().split("\n", 1)[0].strip()
    if len(title) > 120:
        title = title[:117].rstrip() + "..."
    return title


def detect_chapters_heuristic(
    pages: list[tuple[int, list[str]]],
) -> list[CapituloInput]:
    found: list[CapituloInput] = []
    seen_pages: set[int] = set()
    for page_num, paragraphs in pages:
        if not paragraphs:
            continue
        for para in paragraphs[:2]:
            text = para.strip()
            if not text or not _matches_chapter_heading(text):
                continue
            if page_num in seen_pages:
                break
            found.append(
                CapituloInput(
                    nombre=_normalize_chapter_title(text),
                    pagina=page_num,
                )
            )
            seen_pages.add(page_num)
            break
    found.sort(key=lambda x: x.pagina)
    if len(found) < 2:
        return []
    return found


def _build_page_text_summary(
    pages: list[tuple[int, list[str]]],
) -> str:
    lines: list[str] = []
    total = 0
    for page_num, paragraphs in pages:
        snippet = ""
        if paragraphs:
            snippet = paragraphs[0].strip().replace("\n", " ")
            if len(snippet) > _PAGE_SUMMARY_SNIPPET:
                snippet = snippet[: _PAGE_SUMMARY_SNIPPET - 3].rstrip() + "..."
        line = f"Pág. {page_num}: {snippet or '(vacía)'}"
        if total + len(line) + 1 > _MAX_LLM_PAGE_SUMMARY_CHARS:
            break
        lines.append(line)
        total += len(line) + 1
    return "\n".join(lines)


async def detect_chapters_from_page_texts_llm(
    pages: list[tuple[int, list[str]]], page_count: int | None
) -> list[CapituloInput]:
    summary = _build_page_text_summary(pages)
    if not summary.strip():
        raise OllamaError("No hay texto limpio disponible para detectar capítulos.")
    total = page_count or (pages[-1][0] if pages else 1)
    prompt = f"""Analiza el siguiente resumen del inicio de cada página de un libro PDF ({total} páginas en total).
Cada línea muestra el número de página y el primer párrafo (truncado).

Identifica los capítulos o secciones principales del libro y la página donde comienza cada uno.
Ignora prefacio, agradecimientos, páginas en blanco y anexos.
Los números de página deben estar entre 1 y {total}.

Resumen por página:
{summary}
"""
    system = (
        "Eres un asistente que extrae metadatos de libros. "
        "Devuelve únicamente JSON válido según el schema."
    )
    result = await chat_structured(
        prompt,
        LlmChapterExtraction,
        system=system,
    )
    if not result.items:
        raise OllamaError("El LLM no encontró capítulos en el texto.")
    return result.items


def should_run_text_scan(meta: CapitulosMetadata) -> bool:
    if meta.status == "ready" and meta.items:
        return False
    if meta.source == "outline":
        return False
    if meta.status == "loading" and meta.source == "llm":
        return False
    return True


async def _run_text_scan(doc_id: str) -> None:
    try:
        with SessionLocal() as session:
            doc = session.get(Document, doc_id)
            if doc is None:
                return
            page_count = doc.page_count
            pages = list_all_ready_paragraphs(session, doc_id)

        if not pages:
            raise OllamaError("No hay páginas con texto listo.")

        inputs = detect_chapters_heuristic(pages)
        source: str = "text_scan"

        if len(inputs) < 2:
            llm_inputs = await detect_chapters_from_page_texts_llm(pages, page_count)
            inputs = llm_inputs
            source = "text_scan_llm"

        with SessionLocal() as session:
            doc = session.get(Document, doc_id)
            if doc is None:
                return
            clamped = [
                CapituloInput(
                    nombre=x.nombre,
                    pagina=_clamp_page(x.pagina, doc.page_count),
                )
                for x in inputs
                if x.nombre.strip()
            ]
            items = compute_longitudes(clamped, doc.page_count)
            set_capitulos(
                doc,
                CapitulosMetadata(
                    items=items,
                    status="ready",
                    source=source,
                    error=None,
                ),
            )
            session.commit()
            logger.info(
                "Text-scan chapter detection ready for doc %s (%d chapters, source=%s)",
                doc_id,
                len(items),
                source,
            )
            maybe_start_summaries(doc_id)
    except Exception as exc:
        logger.warning("Text-scan chapter detection failed for doc %s: %s", doc_id, exc)
        with SessionLocal() as session:
            doc = session.get(Document, doc_id)
            if doc is None:
                return
            set_capitulos(
                doc,
                CapitulosMetadata(
                    items=[],
                    status="failed",
                    source="text_scan",
                    error=str(exc),
                ),
            )
            session.commit()
    finally:
        _text_scan_tasks.discard(doc_id)


def start_text_scan_detection(session: Session, doc: Document) -> CapitulosMetadata | None:
    meta = get_capitulos(doc)
    if not should_run_text_scan(meta):
        return None
    if doc.id in _text_scan_tasks:
        return get_capitulos(doc)

    meta = CapitulosMetadata(
        items=[],
        status="loading",
        source="text_scan",
        error=None,
    )
    set_capitulos(doc, meta)
    session.commit()
    session.refresh(doc)

    doc_id = doc.id
    _text_scan_tasks.add(doc_id)
    asyncio.create_task(_run_text_scan(doc_id))
    return meta


def maybe_start_text_scan_after_bulk(doc_id: str) -> None:
    if doc_id in _detection_tasks:
        return
    with SessionLocal() as session:
        doc = session.get(Document, doc_id)
        if doc is None:
            return
        if not should_run_text_scan(get_capitulos(doc)):
            return
        if doc_id in _text_scan_tasks:
            return
        start_text_scan_detection(session, doc)
