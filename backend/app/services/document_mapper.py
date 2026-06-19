from datetime import datetime, timezone

from app.models.document import Document
from app.schemas.chapter import CapitulosMetadata
from app.schemas.document import DocumentMetadata, DocumentSummary
from app.services.capitulos_store import get_capitulos
from app.services.resumenes_store import get_resumenes


def document_to_metadata(doc: Document) -> DocumentMetadata:
    capitulos = get_capitulos(doc)
    return DocumentMetadata(
        id=doc.id,
        filename=doc.filename,
        uploader_ip=doc.uploader_ip,
        size_bytes=doc.size_bytes,
        mime_type=doc.mime_type,
        storage_key=doc.storage_key,
        status=doc.status,
        created_at=doc.created_at,
        expires_at=doc.expires_at,
        page_count=doc.page_count,
        page_actual=doc.page_actual,
        last_opened_at=doc.last_opened_at,
        capitulos=capitulos,
        resumenes=get_resumenes(doc),
    )


def document_to_summary(doc: Document) -> DocumentSummary:
    return DocumentSummary(
        id=doc.id,
        filename=doc.filename,
        page_count=doc.page_count,
        page_actual=doc.page_actual,
        last_opened_at=doc.last_opened_at,
        capitulos=get_capitulos(doc),
        resumenes=get_resumenes(doc),
    )
