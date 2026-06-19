from app.models.document import Document
from app.schemas.summary import ResumenesMetadata
from app.services.text_normalize import fix_spanish_llm_text


def get_resumenes(doc: Document) -> ResumenesMetadata:
    if not doc.resumenes_json:
        return ResumenesMetadata()
    try:
        meta = ResumenesMetadata.model_validate_json(doc.resumenes_json)
    except Exception:
        return ResumenesMetadata()
    return _fix_resumenes_text(meta)


def _fix_resumenes_text(meta: ResumenesMetadata) -> ResumenesMetadata:
    if not meta.items:
        return meta
    fixed_items = [
        item.model_copy(
            update={
                "nombre": fix_spanish_llm_text(item.nombre),
                "resumen": fix_spanish_llm_text(item.resumen),
            }
        )
        for item in meta.items
    ]
    return meta.model_copy(update={"items": fixed_items})


def set_resumenes(doc: Document, meta: ResumenesMetadata) -> None:
    doc.resumenes_json = meta.model_dump_json(ensure_ascii=False)
