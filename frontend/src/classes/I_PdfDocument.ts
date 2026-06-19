import type { I_CapitulosMetadata } from './I_Capitulo.ts'
import type { I_ResumenesMetadata } from './I_ResumenCapitulo.ts'

export interface I_PdfDocument {
  id: string
  filename: string
  sizeBytes: number
  mimeType: string
  status: string
  pageCount: number | null
  pageActual: number
  createdAt: string
  expiresAt: string
  lastOpenedAt: string | null
  capitulos: I_CapitulosMetadata
  resumenes: I_ResumenesMetadata
}

export interface I_PdfDocumentSummary {
  id: string
  filename: string
  pageCount: number | null
  pageActual: number
  lastOpenedAt: string | null
  capitulos: I_CapitulosMetadata
}
