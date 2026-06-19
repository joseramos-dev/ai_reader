import type {
  I_CapituloInput,
  I_CapitulosMetadata,
} from '../classes/I_Capitulo.ts'
import type { I_PdfDocument, I_PdfDocumentSummary } from '../classes/I_PdfDocument.ts'
import type { I_ResumenesMetadata } from '../classes/I_ResumenCapitulo.ts'
import { API_BASE, apiFetch } from './client.ts'

interface CapituloDto {
  numero: number
  nombre: string
  pagina: number
  longitud: number
}

interface CapitulosMetadataDto {
  items: CapituloDto[]
  status: I_CapitulosMetadata['status']
  source: I_CapitulosMetadata['source']
  error: string | null
}

interface ResumenCapituloDto {
  numero: number
  nombre: string
  resumen: string
}

interface ResumenesMetadataDto {
  items: ResumenCapituloDto[]
  status: I_ResumenesMetadata['status']
  error: string | null
  total?: number
  percent?: number
}

interface DocumentMetadataDto {
  id: string
  filename: string
  size_bytes: number
  mime_type: string
  status: string
  page_count: number | null
  page_actual: number
  created_at: string
  expires_at: string
  last_opened_at: string | null
  capitulos: CapitulosMetadataDto
  resumenes?: ResumenesMetadataDto
}

interface DocumentSummaryDto {
  id: string
  filename: string
  page_count: number | null
  page_actual: number
  last_opened_at: string | null
  capitulos: CapitulosMetadataDto
  resumenes?: ResumenesMetadataDto
}

function mapCapitulos(dto: CapitulosMetadataDto): I_CapitulosMetadata {
  return {
    items: dto.items.map((c) => ({
      numero: c.numero,
      nombre: c.nombre,
      pagina: c.pagina,
      longitud: c.longitud,
    })),
    status: dto.status,
    source: dto.source,
    error: dto.error,
  }
}

function mapResumenes(dto: ResumenesMetadataDto | undefined): I_ResumenesMetadata {
  if (!dto) {
    return { items: [], status: 'idle', error: null, total: 0, percent: 0 }
  }
  return {
    items: dto.items.map((item) => ({
      numero: item.numero,
      nombre: item.nombre,
      resumen: item.resumen,
    })),
    status: dto.status,
    error: dto.error,
    total: dto.total ?? 0,
    percent: dto.percent ?? 0,
  }
}

function toPdfDocument(dto: DocumentMetadataDto): I_PdfDocument {
  return {
    id: dto.id,
    filename: dto.filename,
    sizeBytes: dto.size_bytes,
    mimeType: dto.mime_type,
    status: dto.status,
    pageCount: dto.page_count,
    pageActual: dto.page_actual,
    createdAt: dto.created_at,
    expiresAt: dto.expires_at,
    lastOpenedAt: dto.last_opened_at,
    capitulos: mapCapitulos(dto.capitulos),
    resumenes: mapResumenes(dto.resumenes),
  }
}

function toPdfDocumentSummary(dto: DocumentSummaryDto): I_PdfDocumentSummary {
  return {
    id: dto.id,
    filename: dto.filename,
    pageCount: dto.page_count,
    pageActual: dto.page_actual,
    lastOpenedAt: dto.last_opened_at,
    capitulos: mapCapitulos(dto.capitulos),
  }
}

export async function uploadDocument(file: File): Promise<I_PdfDocument> {
  const formData = new FormData()
  formData.append('file', file)
  const { data, status } = await apiFetch<DocumentMetadataDto>(
    '/api/v1/documents/upload',
    { method: 'POST', body: formData },
  )
  if (status >= 400) {
    throw new Error('No se pudo subir el PDF.')
  }
  return toPdfDocument(data)
}

export async function listRecentDocuments(): Promise<I_PdfDocumentSummary[]> {
  const { data, status } = await apiFetch<DocumentSummaryDto[]>(
    '/api/v1/documents/recent',
  )
  if (status >= 400) {
    throw new Error('No se pudieron cargar los documentos recientes.')
  }
  return data.map(toPdfDocumentSummary)
}

export async function getRecentDocumentsCount(): Promise<number> {
  const { data, status } = await apiFetch<{ count: number }>(
    '/api/v1/documents/recent/count',
  )
  if (status >= 400) {
    throw new Error('No se pudo obtener el número de documentos recientes.')
  }
  return data.count
}

export async function getDocument(id: string): Promise<I_PdfDocument> {
  const { data, status } = await apiFetch<DocumentMetadataDto>(
    `/api/v1/documents/${id}`,
  )
  if (status >= 400) {
    throw new Error('Documento no encontrado.')
  }
  return toPdfDocument(data)
}

export async function deleteDocument(id: string): Promise<void> {
  const { status } = await apiFetch<Record<string, never>>(
    `/api/v1/documents/${id}`,
    { method: 'DELETE' },
  )
  if (status >= 400) {
    throw new Error('No se pudo eliminar el documento.')
  }
}

export async function updatePageActual(
  id: string,
  page: number,
): Promise<void> {
  await apiFetch(`/api/v1/documents/${id}`, {
    method: 'PATCH',
    body: JSON.stringify({ page_actual: page }),
  })
}

export async function putOutlineChapters(
  id: string,
  items: I_CapituloInput[],
): Promise<I_CapitulosMetadata> {
  const { data, status } = await apiFetch<CapitulosMetadataDto>(
    `/api/v1/documents/${id}/capitulos`,
    {
      method: 'PUT',
      body: JSON.stringify({ items }),
    },
  )
  if (status >= 400) {
    throw new Error('No se pudieron guardar los capítulos.')
  }
  return mapCapitulos(data)
}

export async function detectChapters(id: string): Promise<I_CapitulosMetadata> {
  const { data, status } = await apiFetch<CapitulosMetadataDto>(
    `/api/v1/documents/${id}/capitulos/detect`,
    { method: 'POST' },
  )
  if (status >= 400) {
    throw new Error('No se pudo iniciar la detección de capítulos.')
  }
  return mapCapitulos(data)
}

export function getDocumentFileUrl(id: string): string {
  return `${API_BASE}/api/v1/documents/${id}/file`
}

export async function refreshDocumentCapitulos(
  id: string,
): Promise<I_CapitulosMetadata> {
  const doc = await getDocument(id)
  return doc.capitulos
}

export async function refreshDocumentResumenes(
  id: string,
): Promise<I_ResumenesMetadata> {
  const { data, status } = await apiFetch<ResumenesMetadataDto>(
    `/api/v1/documents/${id}/resumenes`,
  )
  if (status >= 400) {
    throw new Error('No se pudieron cargar los resúmenes.')
  }
  return mapResumenes(data)
}

export async function generateSummaries(
  id: string,
): Promise<I_ResumenesMetadata> {
  const { data, status } = await apiFetch<ResumenesMetadataDto & { detail?: string }>(
    `/api/v1/documents/${id}/resumenes/generate`,
    { method: 'POST' },
  )
  if (status >= 400) {
    throw new Error(
      data.detail ?? 'No se pudo iniciar la generación de resúmenes.',
    )
  }
  return mapResumenes(data)
}
