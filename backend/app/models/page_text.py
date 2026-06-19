from datetime import datetime

from sqlalchemy import ForeignKey
from sqlalchemy.orm import Mapped, mapped_column

from app.db import Base


class PageText(Base):
    __tablename__ = "page_texts"

    document_id: Mapped[str] = mapped_column(
        ForeignKey("documents.id", ondelete="CASCADE"),
        primary_key=True,
    )
    page_number: Mapped[int] = mapped_column(primary_key=True)
    paragraphs_json: Mapped[str | None] = mapped_column(default=None)
    status: Mapped[str] = mapped_column(default="idle")
    error: Mapped[str | None] = mapped_column(default=None)
    cleaner_version: Mapped[int] = mapped_column(default=0)
    updated_at: Mapped[datetime | None] = mapped_column(default=None)
