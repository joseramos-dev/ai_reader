from app.models.document import Document
from app.schemas.chapter import CapitulosMetadata

_DEFAULT = CapitulosMetadata()


def get_capitulos(doc: Document) -> CapitulosMetadata:
    if not doc.capitulos_json:
        return CapitulosMetadata()
    try:
        return CapitulosMetadata.model_validate_json(doc.capitulos_json)
    except Exception:
        return CapitulosMetadata()


def set_capitulos(doc: Document, meta: CapitulosMetadata) -> None:
    doc.capitulos_json = meta.model_dump_json(ensure_ascii=False)
