import logging
from pathlib import Path

from sqlalchemy import create_engine, inspect, text
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker

from app.config import settings

logger = logging.getLogger(__name__)

engine = create_engine(
    settings.db_url, connect_args={"check_same_thread": False}
)
SessionLocal = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)


class Base(DeclarativeBase):
    pass


def _db_path() -> Path | None:
    prefix = "sqlite:///"
    if settings.db_url.startswith(prefix):
        return Path(settings.db_url[len(prefix):])
    return None


def init_db() -> None:
    """Create storage/data directories and all tables."""
    Path(settings.upload_dir).mkdir(parents=True, exist_ok=True)
    db_path = _db_path()
    if db_path is not None:
        db_path.parent.mkdir(parents=True, exist_ok=True)

    # Import models so they register with Base before create_all.
    from app.models import document, page_text  # noqa: F401

    Base.metadata.create_all(bind=engine)
    _migrate_documents_columns()
    _migrate_page_texts_columns()


def _migrate_documents_columns() -> None:
    """Add new columns to existing SQLite databases (create_all is no-op for alters)."""
    if not settings.db_url.startswith("sqlite:///"):
        return
    with engine.connect() as conn:
        cols = {c["name"] for c in inspect(conn).get_columns("documents")}
        if "capitulos_json" not in cols:
            conn.execute(text("ALTER TABLE documents ADD COLUMN capitulos_json TEXT"))
            logger.info("Migrated documents: added capitulos_json")
        if "last_opened_at" not in cols:
            conn.execute(text("ALTER TABLE documents ADD COLUMN last_opened_at DATETIME"))
            logger.info("Migrated documents: added last_opened_at")
        if "resumenes_json" not in cols:
            conn.execute(text("ALTER TABLE documents ADD COLUMN resumenes_json TEXT"))
            logger.info("Migrated documents: added resumenes_json")
        conn.execute(
            text(
                "UPDATE documents SET last_opened_at = created_at "
                "WHERE last_opened_at IS NULL"
            )
        )
        conn.commit()


def _migrate_page_texts_columns() -> None:
    if not settings.db_url.startswith("sqlite:///"):
        return
    with engine.connect() as conn:
        inspector = inspect(conn)
        if "page_texts" not in inspector.get_table_names():
            return
        cols = {c["name"] for c in inspector.get_columns("page_texts")}
        if "cleaner_version" not in cols:
            conn.execute(
                text("ALTER TABLE page_texts ADD COLUMN cleaner_version INTEGER DEFAULT 0")
            )
            logger.info("Migrated page_texts: added cleaner_version")
        conn.commit()


def get_session() -> Session:
    session = SessionLocal()
    try:
        yield session
    finally:
        session.close()
