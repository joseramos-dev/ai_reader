import type {
  I_PageTextMetadata,
  I_PageTextProcessingStatus,
} from '../classes/I_PageText.ts'
import { apiFetch } from './client.ts'

interface PageTextDto {
  page: number
  paragraphs: string[]
  status: I_PageTextMetadata['status']
  error: string | null
}

function mapPageText(dto: PageTextDto): I_PageTextMetadata {
  return {
    page: dto.page,
    paragraphs: dto.paragraphs,
    status: dto.status,
    error: dto.error,
  }
}

const POLL_INTERVAL_MS = 500
const MAX_POLL_ATTEMPTS = 240
const READING_MAX_WAIT_MS = 8000

interface PageTextStatusDto {
  total: number
  ready: number
  loading: number
  failed: number
  idle: number
  percent: number
  is_complete: boolean
}

function mapProcessingStatus(dto: PageTextStatusDto): I_PageTextProcessingStatus {
  return {
    total: dto.total,
    ready: dto.ready,
    loading: dto.loading,
    failed: dto.failed,
    idle: dto.idle,
    percent: dto.percent,
    is_complete: dto.is_complete,
  }
}

export async function getPageTextStatus(
  documentId: string,
): Promise<I_PageTextProcessingStatus> {
  const { data, status } = await apiFetch<PageTextStatusDto>(
    `/api/v1/documents/${documentId}/pages/status`,
  )
  if (status >= 400) {
    throw new Error('No se pudo obtener el estado del texto.')
  }
  return mapProcessingStatus(data)
}

export async function getPageText(
  documentId: string,
  page: number,
): Promise<I_PageTextMetadata> {
  const { data, status } = await apiFetch<PageTextDto>(
    `/api/v1/documents/${documentId}/pages/${page}/text`,
  )
  if (status >= 400) {
    throw new Error('No se pudo obtener el texto de la página.')
  }
  return mapPageText(data)
}

export async function waitForPageParagraphs(
  documentId: string,
  page: number,
): Promise<string[]> {
  for (let attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt += 1) {
    const meta = await getPageText(documentId, page)
    if (meta.status === 'ready') {
      return meta.paragraphs
    }
    if (meta.status === 'failed') {
      throw new Error(meta.error ?? 'No se pudo extraer el texto de la página.')
    }
    await new Promise((resolve) => setTimeout(resolve, POLL_INTERVAL_MS))
  }
  throw new Error('Tiempo de espera agotado al extraer el texto de la página.')
}

export async function waitForPageParagraphsForReading(
  documentId: string,
  page: number,
  options?: { maxWaitMs?: number },
): Promise<string[]> {
  const maxWaitMs = options?.maxWaitMs ?? READING_MAX_WAIT_MS
  const maxAttempts = Math.ceil(maxWaitMs / POLL_INTERVAL_MS)

  for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
    const meta = await getPageText(documentId, page)
    if (meta.status === 'ready') {
      return meta.paragraphs
    }
    if (meta.status === 'failed') {
      return []
    }
    await new Promise((resolve) => setTimeout(resolve, POLL_INTERVAL_MS))
  }
  return []
}
