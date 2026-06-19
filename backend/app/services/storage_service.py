import shutil
from pathlib import Path
from typing import BinaryIO

from app.config import settings

_CHUNK = 1024 * 1024  # 1 MB


def full_path(storage_key: str) -> Path:
    return Path(settings.upload_dir) / storage_key


def save_pdf(file_stream: BinaryIO, storage_key: str) -> int:
    """Stream the upload to disk in blocks and return the bytes written."""
    dest = full_path(storage_key)
    dest.parent.mkdir(parents=True, exist_ok=True)
    written = 0
    with dest.open("wb") as out:
        while True:
            block = file_stream.read(_CHUNK)
            if not block:
                break
            out.write(block)
            written += len(block)
    return written


def delete_pdf(storage_key: str) -> None:
    dest = full_path(storage_key)
    dest.unlink(missing_ok=True)
