const PHRASE_PATTERN =
  /(?:[^.?!¿¡"]|\.(?![.\s]))+(?:\.{3}|…|[.?!¿¡"]+)\s*|(?:[^.?!¿¡"]|\.(?![.\s]))+(?:\.{3}|…)?$/gu

export function splitIntoPhrases(text: string): string[] {
  const normalized = text.replace(/\s+/g, ' ').trim()
  if (!normalized) {
    return []
  }

  const matches = normalized.match(PHRASE_PATTERN)
  if (!matches) {
    return [normalized]
  }

  return matches.map((phrase) => phrase.trim()).filter(Boolean)
}
