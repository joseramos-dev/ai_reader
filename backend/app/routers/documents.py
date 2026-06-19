from fastapi import APIRouter, Depends, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse
from sqlalchemy.orm import Session

from app.config import settings
from app.db import get_session
from app.schemas.chapter import CapitulosMetadata, CapitulosOutlineUpdate
from app.schemas.document import (
    DocumentMetadata,
    DocumentSummary,
    PageActualUpdate,
    RecentDocumentsCount,
)
from app.schemas.page_text import PageTextMetadata, PageTextProcessingStatus
from app.schemas.summary import ResumenesMetadata
from app.services import chapter_service, document_service, page_text_service, storage_service, summary_service
from app.services.document_mapper import document_to_metadata, document_to_summary
from app.services.resumenes_store import get_resumenes

router = APIRouter(prefix="/api/v1/documents", tags=["documents"])


def _client_ip(request: Request) -> str | None:
    forwarded = request.headers.get("x-forwarded-for")
    if forwarded:
        return forwarded.split(",")[0].strip()
    return request.client.host if request.client else None


def _is_pdf(file: UploadFile) -> bool:
    if file.content_type == "application/pdf":
        return True
    return bool(file.filename and file.filename.lower().endswith(".pdf"))


@router.post("/upload", response_model=DocumentMetadata)
async def upload_document(
    request: Request,
    file: UploadFile,
    session: Session = Depends(get_session),
) -> DocumentMetadata:
    if not _is_pdf(file):
        raise HTTPException(status_code=400, detail="The file must be a PDF.")
    doc = document_service.create_document(session, file, _client_ip(request))
    return document_to_metadata(doc)


@router.get("/recent", response_model=list[DocumentSummary])
async def list_recent_documents(
    session: Session = Depends(get_session),
) -> list[DocumentSummary]:
    docs = document_service.list_recent(session, settings.chapters_recent_limit)
    return [document_to_summary(d) for d in docs]


@router.get("/recent/count", response_model=RecentDocumentsCount)
async def count_recent_documents(
    session: Session = Depends(get_session),
) -> RecentDocumentsCount:
    count = document_service.count_recent(session, settings.chapters_recent_limit)
    return RecentDocumentsCount(count=count)


@router.delete("/{doc_id}")
async def delete_document(
    doc_id: str, session: Session = Depends(get_session)
) -> dict[str, bool]:
    if not document_service.delete_document(session, doc_id):
        raise HTTPException(status_code=404, detail="Document not found.")
    return {"deleted": True}


@router.get("/{doc_id}", response_model=DocumentMetadata)
async def get_document(
    doc_id: str, session: Session = Depends(get_session)
) -> DocumentMetadata:
    doc = document_service.get_active(session, doc_id, touch_open=True)
    if doc is None:
        raise HTTPException(status_code=404, detail="Document not found.")
    return document_to_metadata(doc)


@router.patch("/{doc_id}", response_model=DocumentMetadata)
async def update_page_actual(
    doc_id: str,
    body: PageActualUpdate,
    session: Session = Depends(get_session),
) -> DocumentMetadata:
    doc = document_service.get_active(session, doc_id)
    if doc is None:
        raise HTTPException(status_code=404, detail="Document not found.")
    document_service.set_page_actual(session, doc, body.page_actual)
    return document_to_metadata(doc)


@router.put("/{doc_id}/capitulos", response_model=CapitulosMetadata)
async def put_outline_chapters(
    doc_id: str,
    body: CapitulosOutlineUpdate,
    session: Session = Depends(get_session),
) -> CapitulosMetadata:
    doc = document_service.get_active(session, doc_id)
    if doc is None:
        raise HTTPException(status_code=404, detail="Document not found.")
    if not body.items:
        raise HTTPException(status_code=400, detail="Se requiere al menos un capítulo.")
    return chapter_service.save_outline_chapters(session, doc, body.items)


@router.post(
    "/{doc_id}/capitulos/detect",
    response_model=CapitulosMetadata,
    status_code=202,
)
async def detect_chapters(
    doc_id: str,
    session: Session = Depends(get_session),
) -> CapitulosMetadata:
    doc = document_service.get_active(session, doc_id)
    if doc is None:
        raise HTTPException(status_code=404, detail="Document not found.")
    meta = chapter_service.start_llm_detection(session, doc)
    return meta


@router.get("/{doc_id}/resumenes", response_model=ResumenesMetadata)
async def get_resumenes_endpoint(
    doc_id: str, session: Session = Depends(get_session)
) -> ResumenesMetadata:
    doc = document_service.get_active(session, doc_id)
    if doc is None:
        raise HTTPException(status_code=404, detail="Document not found.")
    return get_resumenes(doc)


@router.post(
    "/{doc_id}/resumenes/generate",
    response_model=ResumenesMetadata,
    status_code=202,
)
async def generate_resumenes(
    doc_id: str, session: Session = Depends(get_session)
) -> ResumenesMetadata:
    doc = document_service.get_active(session, doc_id)
    if doc is None:
        raise HTTPException(status_code=404, detail="Document not found.")
    try:
        return summary_service.restart_summaries(session, doc)
    except OllamaError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.get("/{doc_id}/pages/status", response_model=PageTextProcessingStatus)
async def get_page_text_status(
    doc_id: str,
    session: Session = Depends(get_session),
) -> PageTextProcessingStatus:
    doc = document_service.get_active(session, doc_id)
    if doc is None:
        raise HTTPException(status_code=404, detail="Document not found.")
    return page_text_service.get_page_text_status(session, doc)


@router.get("/{doc_id}/pages/{page_number}/text", response_model=PageTextMetadata)
async def get_page_text(
    doc_id: str,
    page_number: int,
    session: Session = Depends(get_session),
) -> PageTextMetadata:
    doc = document_service.get_active(session, doc_id)
    if doc is None:
        raise HTTPException(status_code=404, detail="Document not found.")
    if page_number < 1:
        raise HTTPException(status_code=400, detail="Invalid page number.")
    try:
        return page_text_service.get_or_start_page_text(session, doc, page_number)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.get("/{doc_id}/file")
async def get_document_file(
    doc_id: str, session: Session = Depends(get_session)
) -> FileResponse:
    doc = document_service.get_active(session, doc_id, touch_open=True)
    if doc is None:
        raise HTTPException(status_code=404, detail="Document not found.")
    path = storage_service.full_path(doc.storage_key)
    if not path.exists():
        raise HTTPException(status_code=404, detail="File not found.")
    return FileResponse(
        path, media_type="application/pdf", filename=doc.filename
    )
