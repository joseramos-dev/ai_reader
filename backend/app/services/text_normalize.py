"""Fix common Spanish diacritic mistakes in LLM output."""

from __future__ import annotations

import re
import unicodedata
from typing import Any

_ACUTE_VOWELS = {
    "a": "á",
    "e": "é",
    "i": "í",
    "o": "ó",
    "u": "ú",
    "A": "Á",
    "E": "É",
    "I": "Í",
    "O": "Ó",
    "U": "Ú",
}

# U+00B4 MODIFIER LETTER ACUTE ACCENT (´) before a vowel
_ACUTE_BEFORE_VOWEL = re.compile(r"\u00b4([aeiouAEIOU])")
# U+02DC SMALL TILDE (˜) misplaced around n / after a before n
_TILDE_AFTER_A_BEFORE_N = re.compile(r"([aA])\u02dc\s*([nN])")
_TILDE_ON_N = re.compile(r"([nN])\u02dc")
_TILDE_BEFORE_N = re.compile(r"\u02dc\s*([nN])")


def fix_spanish_llm_text(text: str) -> str:
    if not text:
        return text

    text = unicodedata.normalize("NFC", text)
    text = _ACUTE_BEFORE_VOWEL.sub(lambda m: _ACUTE_VOWELS[m.group(1)], text)
    text = _TILDE_AFTER_A_BEFORE_N.sub(
        lambda m: f"{m.group(1)}{'ñ' if m.group(2) == 'n' else 'Ñ'}",
        text,
    )
    text = _TILDE_ON_N.sub(lambda m: "ñ" if m.group(1) == "n" else "Ñ", text)
    text = _TILDE_BEFORE_N.sub(lambda m: "ñ" if m.group(1) == "n" else "Ñ", text)
    return unicodedata.normalize("NFC", text)


def fix_nested_strings(value: Any) -> Any:
    if isinstance(value, str):
        return fix_spanish_llm_text(value)
    if isinstance(value, list):
        return [fix_nested_strings(item) for item in value]
    if isinstance(value, dict):
        return {key: fix_nested_strings(item) for key, item in value.items()}
    return value
