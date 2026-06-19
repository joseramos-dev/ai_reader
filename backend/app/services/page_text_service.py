"""Page text extraction — bulk at upload with lazy fallback for legacy docs."""

from __future__ import annotations

import asyncio
import json
import logging

from pypdf import PdfReader
from sqlalchemy.orm import Session

from app.db import SessionLocal
from app.models.document import Document
from app.schemas.page_text import PageTextMetadata, PageTextProcessingStatus
from app.services import storage_service
from app.services.page_text_bulk import (
    cancel_bulk_task,
    is_bulk_running,
    start_bulk_processing,
)
from app.services.page_text_cleaner import (
    TEXT_CLEANER_VERSION,
    get_document_layout,
    invalidate_layout_cache,
    merge_cross_page_boundary,
    raw_page_to_paragraphs,
)
from app.services.page_text_store import (
    get_page_text,
    get_processing_status,
    get_ready_paragraphs,
    save_failed,
    save_ready,
    to_metadata,
    upsert_loading,
)

logger = logging.getLogger(__name__)

_extraction_tasks: set[tuple[str, int]] = set()


def _extract_raw_page_text(storage_key: str, page_number: int) -> str:
    path = storage_service.full_path(storage_key)
    reader = PdfReader(str(path))
    if page_number < 1 or page_number > len(reader.pages):
        return ""
    return reader.pages[page_number - 1].extract_text() or ""


def _clean_page_paragraphs(storage_key: str, raw_text: str) -> list[str]:
    full_path = str(storage_service.full_path(storage_key))
    layout = get_document_layout(storage_key, full_path)
    return raw_page_to_paragraphs(raw_text, layout)


def _reconcile_adjacent_pages(
    session: Session,
    doc_id: str,
    page_number: int,
    page_count: int | None,
) -> None:
    if page_count is None:
        return

    if page_number > 1:
        left = get_ready_paragraphs(session, doc_id, page_number - 1)
        right = get_ready_paragraphs(session, doc_id, page_number)
        if left is not None and right is not None:
            merged_left, merged_right, changed = merge_cross_page_boundary(left, right)
            if changed:
                save_ready(session, doc_id, page_number - 1, merged_left)
                save_ready(session, doc_id, page_number, merged_right)

    if page_number < page_count:
        left = get_ready_paragraphs(session, doc_id, page_number)
        right = get_ready_paragraphs(session, doc_id, page_number + 1)
        if left is not None and right is not None:
            merged_left, merged_right, changed = merge_cross_page_boundary(left, right)
            if changed:
                save_ready(session, doc_id, page_number, merged_left)
                save_ready(session, doc_id, page_number + 1, merged_right)


async def _run_extraction(doc_id: str, page_number: int) -> None:
    """Lazy single-page extraction for legacy documents without bulk processing."""
    key = (doc_id, page_number)
    try:
        with SessionLocal() as session:
            doc = session.get(Document, doc_id)
            if doc is None:
                return
            storage_key = doc.storage_key
            page_count = doc.page_count

        raw = _extract_raw_page_text(storage_key, page_number)
        paragraphs = _clean_page_paragraphs(storage_key, raw) if raw.strip() else []

        with SessionLocal() as session:
            save_ready(session, doc_id, page_number, paragraphs)
            _reconcile_adjacent_pages(session, doc_id, page_number, page_count)
    except Exception as exc:
        logger.warning(
            "Page text extraction failed for doc %s page %d: %s",
            doc_id,
            page_number,
            exc,
        )
        with SessionLocal() as session:
            save_failed(session, doc_id, page_number, str(exc))
    finally:
        _extraction_tasks.discard(key)


def get_page_text_status(
    session: Session, doc: Document
) -> PageTextProcessingStatus:
    total = doc.page_count or 0
    return get_processing_status(session, doc.id, total)


def get_or_start_page_text(
    session: Session, doc: Document, page_number: int
) -> PageTextMetadata:
    if doc.page_count is not None and page_number > doc.page_count:
        raise ValueError("Página fuera de rango.")

    row = get_page_text(session, doc.id, page_number)

    if is_bulk_running(doc.id):
        if row is not None:
            return to_metadata(row, page_number)
        return PageTextMetadata(page=page_number, status="loading")

    if row is not None and row.status == "ready":
        version = row.cleaner_version or 0
        if version >= TEXT_CLEANER_VERSION:
            return to_metadata(row, page_number)

    if row is not None and row.status == "loading":
        return to_metadata(row, page_number)

    if row is not None and row.status == "failed":
        return to_metadata(row, page_number)

    key = (doc.id, page_number)
    meta = upsert_loading(session, doc.id, page_number)
    if key not in _extraction_tasks:
        _extraction_tasks.add(key)
        asyncio.create_task(_run_extraction(doc.id, page_number))
    return meta


def delete_document_page_texts(session: Session, document_id: str) -> None:
    from sqlalchemy import delete

    from app.models.page_text import PageText

    cancel_bulk_task(document_id)
    session.execute(delete(PageText).where(PageText.document_id == document_id))
    session.commit()
    keys_to_drop = [k for k in _extraction_tasks if k[0] == document_id]
    for key in keys_to_drop:
        _extraction_tasks.discard(key)
    invalidate_layout_cache()


def trigger_bulk_on_upload(doc_id: str, page_count: int | None) -> None:
    if page_count and page_count > 0:
        start_bulk_processing(doc_id, page_count)
