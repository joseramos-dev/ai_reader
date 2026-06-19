"""Programmatic PDF page text cleaning — headers, footers, hyphens, paragraphs."""

from __future__ import annotations

import re
from collections import Counter
from dataclasses import dataclass, field
from functools import lru_cache

from pypdf import PdfReader

TEXT_CLEANER_VERSION = 3

# Hyphens used for line-break word splits (not compound words like "well-known" mid-line).
_HYPHEN_CHARS = "-‐‑–—"
_HYPHEN_BREAK = re.compile(
    rf"(\w)[{re.escape(_HYPHEN_CHARS)}]\s+(\w)",
    re.UNICODE,
)
_HYPHEN_BREAK_NL = re.compile(
    rf"(\w)[{re.escape(_HYPHEN_CHARS)}]\s*\n\s*(\w)",
    re.UNICODE,
)
_LINE_HYPHEN_SUFFIX = re.compile(
    rf"(\w+)[{re.escape(_HYPHEN_CHARS)}]\s*$",
    re.UNICODE,
)
_PAGE_NUMBER = re.compile(
    r"^\s*(?:"
    r"(?:p[áa]g(?:\.|ina)?\.?\s*)?\d+\s*(?:de\s+\d+)?"
    r"|[-–—]\s*\d+\s*[-–—]"
    r"|\d+\s*/\s*\d+"
    r")\s*$",
    re.IGNORECASE | re.UNICODE,
)
_SENTENCE_END = re.compile(
    r"(?:"
    r"\.{3}"
    r"|…"
    r"|[!?]"
    r"|(?<!\.)\.(?!\.)"
    r")(?:[»\"'\)\]]*)?\s*$",
    re.UNICODE,
)


@dataclass
class DocumentLayout:
    """Repeated lines detected across pages (headers, footers)."""

    header_lines: set[str] = field(default_factory=set)
    footer_lines: set[str] = field(default_factory=set)


def _normalize_line(line: str) -> str:
    return re.sub(r"\s+", " ", line.strip()).lower()


def _fuzzy_line_key(line: str) -> str:
    return re.sub(r"\d+", "#", _normalize_line(line))


def _line_in_repeated_set(line: str, candidates: set[str]) -> bool:
    norm = _normalize_line(line)
    if norm in candidates:
        return True
    return _fuzzy_line_key(line) in candidates


def _is_page_number_line(line: str) -> bool:
    stripped = line.strip()
    if not stripped:
        return False
    if _PAGE_NUMBER.match(stripped):
        return True
    if re.fullmatch(r"\d{1,4}", stripped):
        return True
    return False


def _extract_all_page_lines(full_path: str) -> list[list[str]]:
    reader = PdfReader(str(full_path))
    pages: list[list[str]] = []
    for page in reader.pages:
        raw = page.extract_text() or ""
        pages.append(raw.splitlines())
    return pages


def detect_layout_from_lines_per_page(
    lines_per_page: list[list[str]],
) -> DocumentLayout:
    """Find header/footer lines repeated on many pages from pre-extracted lines."""
    page_count = len(lines_per_page)
    if page_count == 0:
        return DocumentLayout()

    top_counter: Counter[str] = Counter()
    bottom_counter: Counter[str] = Counter()

    for lines in lines_per_page:
        non_empty = [line for line in lines if line.strip()]
        if not non_empty:
            continue
        for line in non_empty[:4]:
            norm = _normalize_line(line)
            if norm and not _is_page_number_line(line):
                top_counter[norm] += 1
                top_counter[_fuzzy_line_key(line)] += 1
        for line in non_empty[-4:]:
            norm = _normalize_line(line)
            if norm and not _is_page_number_line(line):
                bottom_counter[norm] += 1
                bottom_counter[_fuzzy_line_key(line)] += 1

    threshold = max(2, int(page_count * 0.35))
    header_lines = {
        line
        for line, count in top_counter.items()
        if count >= threshold and 2 <= len(line) <= 160
    }
    footer_lines = {
        line
        for line, count in bottom_counter.items()
        if count >= threshold and 2 <= len(line) <= 160
    }

    return DocumentLayout(header_lines=header_lines, footer_lines=footer_lines)


def detect_document_layout(storage_key: str, *, full_path: str) -> DocumentLayout:
    """Find header/footer lines repeated on many pages."""
    try:
        all_lines = _extract_all_page_lines(full_path)
    except Exception:
        return DocumentLayout()

    return detect_layout_from_lines_per_page(all_lines)


@lru_cache(maxsize=32)
def _cached_layout(storage_key: str, full_path: str) -> DocumentLayout:
    return detect_document_layout(storage_key, full_path=full_path)


def get_document_layout(storage_key: str, full_path: str) -> DocumentLayout:
    return _cached_layout(storage_key, full_path)


def invalidate_layout_cache() -> None:
    _cached_layout.cache_clear()


def fix_intrapage_hyphens(text: str) -> str:
    """Join words split by a line-break hyphen: obs- truye → obstruye."""
    for _ in range(20):
        updated = _HYPHEN_BREAK_NL.sub(r"\1\2", text)
        updated = _HYPHEN_BREAK.sub(r"\1\2", updated)
        if updated == text:
            return text
        text = updated
    return text


def _strip_repeated_margins(lines: list[str], layout: DocumentLayout) -> list[str]:
    result = list(lines)

    while result and (
        _line_in_repeated_set(result[0], layout.header_lines)
        or _is_page_number_line(result[0])
    ):
        result.pop(0)

    while result and (
        _line_in_repeated_set(result[-1], layout.footer_lines)
        or _is_page_number_line(result[-1])
    ):
        result.pop(-1)

    return result


def _should_start_new_paragraph(prev_line: str, next_line: str, raw_next: str) -> bool:
    prev = prev_line.strip()
    nxt = next_line.strip()
    if not prev or not nxt:
        return True
    if _is_indented_paragraph_start(raw_next):
        return True
    if _SENTENCE_END.search(prev) and nxt[0].isupper():
        return True
    if _SENTENCE_END.search(prev) and nxt[0].isdigit():
        return True
    return False


def _is_indented_paragraph_start(line: str) -> bool:
    return bool(re.match(r"^\s{2,}\S", line))


def _join_line_to_text(text: str, part: str) -> str:
    match = _LINE_HYPHEN_SUFFIX.search(text)
    if match:
        return text[: match.start()] + match.group(1) + part
    return f"{text} {part}"


def _join_wrapped_lines(lines: list[str]) -> str:
    if not lines:
        return ""
    text = lines[0].strip()
    for line in lines[1:]:
        part = line.strip()
        if not part:
            continue
        text = _join_line_to_text(text, part)
    return fix_intrapage_hyphens(re.sub(r"\s+", " ", text).strip())


def raw_page_to_paragraphs(
    raw_text: str,
    layout: DocumentLayout,
) -> list[str]:
    """Convert raw pypdf text into clean reading paragraphs."""
    if not raw_text.strip():
        return []

    lines = raw_text.splitlines()
    lines = _strip_repeated_margins(lines, layout)

    paragraphs: list[str] = []
    buffer: list[str] = []
    raw_buffer: list[str] = []

    for line in lines:
        if not line.strip():
            if buffer:
                paragraph = _join_wrapped_lines(buffer)
                if paragraph:
                    paragraphs.append(paragraph)
                buffer = []
                raw_buffer = []
            continue

        if buffer and _should_start_new_paragraph(buffer[-1], line, line):
            paragraph = _join_wrapped_lines(buffer)
            if paragraph:
                paragraphs.append(paragraph)
            buffer = [line.strip()]
            raw_buffer = [line]
        else:
            buffer.append(line.strip())
            raw_buffer.append(line)

    if buffer:
        paragraph = _join_wrapped_lines(buffer)
        if paragraph:
            paragraphs.append(paragraph)

    return [fix_intrapage_hyphens(p) for p in paragraphs if p.strip()]


_TRAILING_HYPHEN_WORD = re.compile(
    rf"^(.+?\s)?(\w+)[{re.escape(_HYPHEN_CHARS)}]\s*$",
    re.UNICODE,
)


def last_paragraph_has_trailing_hyphen(paragraphs: list[str]) -> bool:
    if not paragraphs:
        return False
    return bool(_TRAILING_HYPHEN_WORD.search(paragraphs[-1]))


def merge_cross_page_boundary(
    left: list[str],
    right: list[str],
) -> tuple[list[str], list[str], bool]:
    if not left or not right:
        return left, right, False

    last_para = left[-1]
    match = _TRAILING_HYPHEN_WORD.match(last_para)
    if not match:
        return left, right, False

    prefix = match.group(1) or ""
    stem = match.group(2)

    first_para = right[0].strip()
    if not first_para:
        return left, right, False

    word_match = re.match(r"^(\S+)([\s\S]*)$", first_para)
    if not word_match:
        return left, right, False

    suffix = word_match.group(1)
    remainder = word_match.group(2).lstrip()

    merged_left = list(left)
    merged_right = list(right)
    merged_left[-1] = f"{prefix}{stem}{suffix}".rstrip()

    if remainder:
        merged_right[0] = remainder
    else:
        merged_right = merged_right[1:]

    return merged_left, merged_right, True
