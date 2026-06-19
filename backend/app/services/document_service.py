import logging
import uuid
from datetime import datetime, timedelta, timezone

from fastapi import UploadFile
from pypdf import PdfReader
from sqlalchemy import desc, func, select
from sqlalchemy.orm import Session

from app.config import settings
from app.models.document import Document
from app.schemas.chapter import CapitulosMetadata
from app.services import storage_service
from app.services.page_text_service import delete_document_page_texts, trigger_bulk_on_upload
from app.services.capitulos_store import set_capitulos

logger = logging.getLogger(__name__)


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _ttl() -> timedelta:
    return timedelta(days=settings.document_ttl_days)


def _count_pages(storage_key: str) -> int | None:
    try:
        reader = PdfReader(storage_service.full_path(storage_key))
        return len(reader.pages)
    except Exception as exc:
        logger.warning("Could not count PDF pages: %s", exc)
        return None


def create_document(
    session: Session, file: UploadFile, uploader_ip: str | None
) -> Document:
    doc_id = uuid.uuid4().hex
    storage_key = f"{doc_id}.pdf"
    size_bytes = storage_service.save_pdf(file.file, storage_key)
    page_count = _count_pages(storage_key)
    now = _now()

    doc = Document(
        id=doc_id,
        filename=file.filename or storage_key,
        uploader_ip=uploader_ip,
        size_bytes=size_bytes,
        mime_type=file.content_type or "application/pdf",
        storage_key=storage_key,
        status="ready",
        created_at=now,
        expires_at=now + _ttl(),
        page_count=page_count,
        page_actual=1,
        last_opened_at=now,
    )
    set_capitulos(doc, CapitulosMetadata())
    session.add(doc)
    session.commit()
    session.refresh(doc)
    trigger_bulk_on_upload(doc.id, doc.page_count)
    return doc


def _is_expired(doc: Document) -> bool:
    expires = doc.expires_at
    if expires.tzinfo is None:
        expires = expires.replace(tzinfo=timezone.utc)
    return expires < _now()


def get_active(session: Session, doc_id: str, *, touch_open: bool = False) -> Document | None:
    doc = session.get(Document, doc_id)
    if doc is None or _is_expired(doc):
        return None
    if touch_open:
        record_open(session, doc)
    return doc


def record_open(session: Session, doc: Document) -> Document:
    now = _now()
    doc.last_opened_at = now
    doc.expires_at = now + _ttl()
    session.commit()
    session.refresh(doc)
    return doc


def touch(session: Session, doc: Document) -> Document:
    return record_open(session, doc)


def set_page_actual(session: Session, doc: Document, page: int) -> Document:
    doc.page_actual = page
    now = _now()
    doc.expires_at = now + _ttl()
    if doc.last_opened_at is None:
        doc.last_opened_at = now
    session.commit()
    session.refresh(doc)
    return doc


def list_recent(session: Session, limit: int | None = None) -> list[Document]:
    now = _now()
    cap = limit if limit is not None else settings.chapters_recent_limit
    stmt = (
        select(Document)
        .where(Document.expires_at >= now)
        .order_by(desc(func.coalesce(Document.last_opened_at, Document.created_at)))
        .limit(cap)
    )
    return list(session.scalars(stmt).all())


def count_recent(session: Session, limit: int | None = None) -> int:
    now = _now()
    cap = limit if limit is not None else settings.chapters_recent_limit
    total = session.scalar(
        select(func.count())
        .select_from(Document)
        .where(Document.expires_at >= now)
    )
    return min(int(total or 0), cap)


def delete_document(session: Session, doc_id: str) -> bool:
    doc = session.get(Document, doc_id)
    if doc is None:
        return False
    delete_document_page_texts(session, doc_id)
    storage_service.delete_pdf(doc.storage_key)
    session.delete(doc)
    session.commit()
    logger.info("Deleted document %s", doc_id)
    return True


def cleanup_expired(session: Session) -> int:
    now = _now()
    expired = session.scalars(
        select(Document).where(Document.expires_at < now)
    ).all()
    for doc in expired:
        storage_service.delete_pdf(doc.storage_key)
        session.delete(doc)
    session.commit()
    if expired:
        logger.info("Cleaned up %d expired document(s).", len(expired))
    return len(expired)
