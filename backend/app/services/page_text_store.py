import json
from datetime import datetime, timezone

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.models.page_text import PageText
from app.schemas.page_text import PageTextMetadata, PageTextProcessingStatus
from app.services.page_text_cleaner import TEXT_CLEANER_VERSION


def _now() -> datetime:
    return datetime.now(timezone.utc)


def get_page_text(session: Session, document_id: str, page: int) -> PageText | None:
    return session.get(PageText, {"document_id": document_id, "page_number": page})


def to_metadata(row: PageText | None, page: int) -> PageTextMetadata:
    if row is None:
        return PageTextMetadata(page=page, status="idle")
    paragraphs: list[str] = []
    if row.paragraphs_json:
        try:
            parsed = json.loads(row.paragraphs_json)
            if isinstance(parsed, list):
                paragraphs = [str(p) for p in parsed if str(p).strip()]
        except json.JSONDecodeError:
            pass
    status = row.status if row.status in ("idle", "loading", "ready", "failed") else "idle"
    return PageTextMetadata(
        page=page,
        paragraphs=paragraphs,
        status=status,
        error=row.error,
    )


def seed_loading_pages(session: Session, document_id: str, page_count: int) -> None:
    now = _now()
    for page in range(1, page_count + 1):
        row = get_page_text(session, document_id, page)
        if row is None:
            session.add(
                PageText(
                    document_id=document_id,
                    page_number=page,
                    status="loading",
                    error=None,
                    updated_at=now,
                )
            )
        elif row.status != "ready":
            row.status = "loading"
            row.error = None
            row.updated_at = now
    session.commit()


def fail_remaining_loading(
    session: Session, document_id: str, error: str
) -> None:
    now = _now()
    rows = (
        session.query(PageText)
        .filter(
            PageText.document_id == document_id,
            PageText.status == "loading",
        )
        .all()
    )
    for row in rows:
        row.status = "failed"
        row.error = error
        row.updated_at = now
    session.commit()


def get_processing_status(
    session: Session, document_id: str, total_pages: int
) -> PageTextProcessingStatus:
    counts: dict[str, int] = {status: 0 for status in ("idle", "loading", "ready", "failed")}
    rows = session.execute(
        select(PageText.status, func.count())
        .where(PageText.document_id == document_id)
        .group_by(PageText.status)
    ).all()
    for status, count in rows:
        if status in counts:
            counts[status] = int(count)

    ready = counts["ready"]
    loading = counts["loading"]
    failed = counts["failed"]
    idle = max(0, total_pages - ready - loading - failed)
    total = max(total_pages, ready + loading + failed + idle)
    processed = ready + failed
    percent = round((processed / total) * 100, 1) if total > 0 else 100.0
    is_complete = total_pages > 0 and loading == 0 and idle == 0

    return PageTextProcessingStatus(
        total=total,
        ready=ready,
        loading=loading,
        failed=failed,
        idle=idle,
        percent=percent,
        is_complete=is_complete,
    )


def upsert_loading(session: Session, document_id: str, page: int) -> PageTextMetadata:
    row = get_page_text(session, document_id, page)
    now = _now()
    if row is None:
        row = PageText(
            document_id=document_id,
            page_number=page,
            status="loading",
            error=None,
            updated_at=now,
        )
        session.add(row)
    else:
        row.status = "loading"
        row.error = None
        row.updated_at = now
    session.commit()
    return to_metadata(row, page)


def get_ready_paragraphs(
    session: Session, document_id: str, page: int
) -> list[str] | None:
    row = get_page_text(session, document_id, page)
    if row is None or row.status != "ready":
        return None
    meta = to_metadata(row, page)
    return meta.paragraphs


def list_all_ready_paragraphs(
    session: Session, document_id: str
) -> list[tuple[int, list[str]]]:
    rows = session.scalars(
        select(PageText)
        .where(
            PageText.document_id == document_id,
            PageText.status == "ready",
        )
        .order_by(PageText.page_number)
    ).all()
    result: list[tuple[int, list[str]]] = []
    for row in rows:
        meta = to_metadata(row, row.page_number)
        result.append((row.page_number, meta.paragraphs))
    return result


def save_ready(
    session: Session, document_id: str, page: int, paragraphs: list[str]
) -> PageTextMetadata:
    row = get_page_text(session, document_id, page)
    now = _now()
    payload = json.dumps(paragraphs, ensure_ascii=False)
    if row is None:
        row = PageText(
            document_id=document_id,
            page_number=page,
            paragraphs_json=payload,
            status="ready",
            error=None,
            cleaner_version=TEXT_CLEANER_VERSION,
            updated_at=now,
        )
        session.add(row)
    else:
        row.paragraphs_json = payload
        row.status = "ready"
        row.error = None
        row.cleaner_version = TEXT_CLEANER_VERSION
        row.updated_at = now
    session.commit()
    return to_metadata(row, page)


def save_failed(
    session: Session, document_id: str, page: int, error: str
) -> PageTextMetadata:
    row = get_page_text(session, document_id, page)
    now = _now()
    if row is None:
        row = PageText(
            document_id=document_id,
            page_number=page,
            status="failed",
            error=error,
            updated_at=now,
        )
        session.add(row)
    else:
        row.status = "failed"
        row.error = error
        row.updated_at = now
    session.commit()
    return to_metadata(row, page)


def delete_for_document(session: Session, document_id: str) -> None:
    for row in session.query(PageText).filter(PageText.document_id == document_id).all():
        session.delete(row)
    session.commit()
