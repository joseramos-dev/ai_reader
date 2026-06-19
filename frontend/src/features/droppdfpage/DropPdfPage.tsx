import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'

import {
  deleteDocument,
  getRecentDocumentsCount,
  listRecentDocuments,
  uploadDocument,
} from '../../api/documentsApi.ts'
import type { I_PdfDocumentSummary } from '../../classes/I_PdfDocument.ts'
import { usePdfDocument } from '../../public/hooks/usePdfDocument.ts'
import { PdfDropzone } from './PdfDropzone.tsx'
import { RecentDocumentsList } from './RecentDocumentsList.tsx'

const RECENT_MAX_ATTEMPTS = 8
const RECENT_RETRY_MS = 1500

async function withRetry<T>(load: () => Promise<T>): Promise<T> {
  let lastError: unknown
  for (let attempt = 1; attempt <= RECENT_MAX_ATTEMPTS; attempt += 1) {
    try {
      return await load()
    } catch (err) {
      lastError = err
      if (attempt < RECENT_MAX_ATTEMPTS) {
        await new Promise((resolve) => setTimeout(resolve, RECENT_RETRY_MS))
      }
    }
  }
  throw lastError
}

export function DropPdfPage() {
  const { setDocument } = usePdfDocument()
  const [error, setError] = useState<string | null>(null)
  const [recentError, setRecentError] = useState<string | null>(null)
  const [isUploading, setIsUploading] = useState(false)
  const [recentDocs, setRecentDocs] = useState<I_PdfDocumentSummary[]>([])
  const [skeletonCount, setSkeletonCount] = useState(0)
  const [isLoadingRecentDetails, setIsLoadingRecentDetails] = useState(false)
  const navigate = useNavigate()

  const refreshRecent = useCallback(async () => {
    setRecentError(null)
    setRecentDocs([])
    setIsLoadingRecentDetails(false)

    try {
      const count = await withRetry(getRecentDocumentsCount)
      setSkeletonCount(count)

      if (count === 0) {
        return
      }

      setIsLoadingRecentDetails(true)
      const docs = await withRetry(listRecentDocuments)
      setRecentDocs(docs)
      setSkeletonCount(docs.length)
    } catch {
      setRecentDocs([])
      setSkeletonCount(0)
      setRecentError(
        'No se pudieron cargar los documentos recientes. Comprueba que el backend esté en marcha.',
      )
    } finally {
      setIsLoadingRecentDetails(false)
    }
  }, [])

  useEffect(() => {
    void refreshRecent()
  }, [refreshRecent])

  async function handleFileAccepted(file: File) {
    setError(null)
    setIsUploading(true)
    try {
      const document = await uploadDocument(file)
      setDocument(document)
      navigate('/viewer')
    } catch {
      setError('No se pudo subir el PDF. Inténtalo de nuevo.')
    } finally {
      setIsUploading(false)
    }
  }

  async function handleDeleteRecent(id: string) {
    try {
      await deleteDocument(id)
      setRecentDocs((prev) => prev.filter((doc) => doc.id !== id))
      setSkeletonCount((prev) => Math.max(0, prev - 1))
    } catch {
      setRecentError('No se pudo eliminar el documento.')
    }
  }

  return (
    <main className="mx-auto flex min-h-svh w-full max-w-2xl flex-col justify-center gap-6 p-6">
      <header className="text-center">
        <h1 className="text-2xl font-semibold text-zinc-900 dark:text-zinc-100">
          Pocket TTS
        </h1>
        <p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">
          Sube un PDF para visualizarlo.
        </p>
      </header>

      <PdfDropzone
        onFileAccepted={handleFileAccepted}
        onError={setError}
        disabled={isUploading}
      />

      <RecentDocumentsList
        documents={recentDocs}
        skeletonCount={skeletonCount}
        isLoadingDetails={isLoadingRecentDetails}
        error={recentError}
        onRetry={() => void refreshRecent()}
        onDelete={handleDeleteRecent}
      />

      {isUploading && (
        <p className="text-center text-sm text-zinc-500 dark:text-zinc-400">
          Subiendo PDF…
        </p>
      )}

      {error && (
        <p className="text-center text-sm text-red-600 dark:text-red-400">
          {error}
        </p>
      )}
    </main>
  )
}
