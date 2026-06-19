from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field

from app.schemas.chapter import CapitulosMetadata
from app.schemas.summary import ResumenesMetadata


class DocumentMetadata(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    filename: str
    uploader_ip: str | None
    size_bytes: int
    mime_type: str
    storage_key: str
    status: str
    created_at: datetime
    expires_at: datetime
    page_count: int | None
    page_actual: int
    last_opened_at: datetime | None = None
    capitulos: CapitulosMetadata = CapitulosMetadata()
    resumenes: ResumenesMetadata = ResumenesMetadata()


class DocumentSummary(BaseModel):
    id: str
    filename: str
    page_count: int | None
    page_actual: int
    last_opened_at: datetime | None
    capitulos: CapitulosMetadata = CapitulosMetadata()
    resumenes: ResumenesMetadata = ResumenesMetadata()


class RecentDocumentsCount(BaseModel):
    count: int = Field(ge=0)


class PageActualUpdate(BaseModel):
    page_actual: int = Field(ge=1)
