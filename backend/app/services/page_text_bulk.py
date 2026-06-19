"""Bulk page text extraction at upload — single PDF read, full-document clean."""

from __future__ import annotations

import asyncio
import logging

from pypdf import PdfReader

from app.db import SessionLocal
from app.models.document import Document
from app.services import storage_service
from app.services.page_text_cleaner import (
    detect_layout_from_lines_per_page,
    merge_cross_page_boundary,
    raw_page_to_paragraphs,
)
from app.services.page_text_store import (
    fail_remaining_loading,
    save_ready,
    seed_loading_pages,
)
from app.services.chapter_service import maybe_start_text_scan_after_bulk
from app.services.summary_service import maybe_start_summaries

logger = logging.getLogger(__name__)

_bulk_tasks: set[str] = set()


def is_bulk_running(document_id: str) -> bool:
    return document_id in _bulk_tasks


def start_bulk_processing(doc_id: str, page_count: int) -> None:
    if page_count < 1:
        return
    if doc_id in _bulk_tasks:
        return

    with SessionLocal() as session:
        seed_loading_pages(session, doc_id, page_count)

    _bulk_tasks.add(doc_id)
    asyncio.create_task(_run_bulk(doc_id))


def _apply_cross_page_merges(all_paragraphs: list[list[str]]) -> list[list[str]]:
    result = [list(p) for p in all_paragraphs]
    for index in range(len(result) - 1):
        merged_left, merged_right, changed = merge_cross_page_boundary(
            result[index],
            result[index + 1],
        )
        if changed:
            result[index] = merged_left
            result[index + 1] = merged_right
    return result


async def _run_bulk(doc_id: str) -> None:
    try:
        with SessionLocal() as session:
            doc = session.get(Document, doc_id)
            if doc is None:
                return
            storage_key = doc.storage_key

        path = storage_service.full_path(storage_key)
        reader = PdfReader(str(path))
        raw_pages = [page.extract_text() or "" for page in reader.pages]
        lines_per_page = [raw.splitlines() for raw in raw_pages]
        layout = detect_layout_from_lines_per_page(lines_per_page)

        all_paragraphs: list[list[str]] = []
        for page_number, raw in enumerate(raw_pages, start=1):
            paragraphs = raw_page_to_paragraphs(raw, layout)
            all_paragraphs.append(paragraphs)
            with SessionLocal() as session:
                save_ready(session, doc_id, page_number, paragraphs)

        merged = _apply_cross_page_merges(all_paragraphs)
        with SessionLocal() as session:
            for page_number, paragraphs in enumerate(merged, start=1):
                if paragraphs != all_paragraphs[page_number - 1]:
                    save_ready(session, doc_id, page_number, paragraphs)

        logger.info(
            "Bulk page text complete for doc %s (%d pages)",
            doc_id,
            len(raw_pages),
        )
        maybe_start_text_scan_after_bulk(doc_id)
        maybe_start_summaries(doc_id)
    except Exception as exc:
        logger.warning("Bulk page text failed for doc %s: %s", doc_id, exc)
        with SessionLocal() as session:
            fail_remaining_loading(session, doc_id, str(exc))
    finally:
        _bulk_tasks.discard(doc_id)


def cancel_bulk_task(document_id: str) -> None:
    _bulk_tasks.discard(document_id)
