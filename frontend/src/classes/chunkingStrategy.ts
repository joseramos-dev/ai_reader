import { splitIntoPhrases } from './phraseSplitter.ts'

export type TtsEngineId = 'pocket_tts' | 'kokoro' | 'piper'

const ENGINE_MAX_CHARS: Record<TtsEngineId, number> = {
  pocket_tts: 700,
  kokoro: 500,
  piper: 600,
}

const pageChunkCache = new Map<string, Promise<string[]>>()

function chunkCacheKey(
  documentId: string,
  pageNumber: number,
  engine: string,
): string {
  return `${documentId}:${pageNumber}:${engine}`
}

export function invalidateChunkCache(): void {
  pageChunkCache.clear()
}

export function getMaxCharsForEngine(engine: string): number {
  return ENGINE_MAX_CHARS[engine as TtsEngineId] ?? 700
}

export function chunkParagraphs(paragraphs: string[], engine: string): string[] {
  const maxChars = getMaxCharsForEngine(engine)
  const result: string[] = []

  for (const paragraph of paragraphs) {
    const trimmed = paragraph.replace(/\s+/g, ' ').trim()
    if (!trimmed) {
      continue
    }

    if (trimmed.length <= maxChars) {
      result.push(trimmed)
      continue
    }

    result.push(...splitIntoPhrases(trimmed))
  }

  return result
}

export function prefetchPageChunks(
  documentId: string,
  pageNumber: number,
  engine: string,
  getParagraphs: (page: number) => Promise<string[]>,
): Promise<string[]> {
  if (pageNumber < 1) {
    return Promise.resolve([])
  }

  const key = chunkCacheKey(documentId, pageNumber, engine)
  let cached = pageChunkCache.get(key)
  if (!cached) {
    cached = getParagraphs(pageNumber).then((paragraphs) =>
      chunkParagraphs(paragraphs, engine),
    )
    void cached.then((chunks) => {
      if (chunks.length === 0) {
        pageChunkCache.delete(key)
      }
    })
    pageChunkCache.set(key, cached)
  }
  return cached
}
