import { splitIntoPhrases } from './phraseSplitter.ts'

export const MAX_PARAGRAPH_CHARS = 700

export function normalizeParagraphChunks(paragraphs: string[]): string[] {
  const result: string[] = []

  for (const paragraph of paragraphs) {
    const trimmed = paragraph.replace(/\s+/g, ' ').trim()
    if (!trimmed) {
      continue
    }

    if (trimmed.length <= MAX_PARAGRAPH_CHARS) {
      result.push(trimmed)
      continue
    }

    result.push(...splitIntoPhrases(trimmed))
  }

  return result
}
