from datetime import datetime

from sqlalchemy.orm import Mapped, mapped_column

from app.db import Base


class Document(Base):
    __tablename__ = "documents"

    id: Mapped[str] = mapped_column(primary_key=True)
    filename: Mapped[str] = mapped_column()
    uploader_ip: Mapped[str | None] = mapped_column(default=None)
    size_bytes: Mapped[int] = mapped_column()
    mime_type: Mapped[str] = mapped_column()
    storage_key: Mapped[str] = mapped_column()
    status: Mapped[str] = mapped_column(default="uploaded")
    created_at: Mapped[datetime] = mapped_column()
    expires_at: Mapped[datetime] = mapped_column()
    page_count: Mapped[int | None] = mapped_column(default=None)
    page_actual: Mapped[int] = mapped_column(default=1)
    capitulos_json: Mapped[str | None] = mapped_column(default=None)
    resumenes_json: Mapped[str | None] = mapped_column(default=None)
    last_opened_at: Mapped[datetime | None] = mapped_column(default=None)
