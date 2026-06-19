import { useNavigate } from 'react-router-dom'

import { getDocument } from '../../api/documentsApi.ts'
import type { I_PdfDocumentSummary } from '../../classes/I_PdfDocument.ts'
import { usePdfDocument } from '../../public/hooks/usePdfDocument.ts'

interface RecentDocumentsListProps {
  documents: I_PdfDocumentSummary[]
  skeletonCount?: number
  isLoadingDetails?: boolean
  error?: string | null
  onRetry?: () => void
  onDelete?: (id: string) => void | Promise<void>
}

const LIST_MAX_HEIGHT = 'max-h-72'

function formatRelative(iso: string | null): string {
  if (!iso) {
    return ''
  }
  const date = new Date(iso)
  const diffMs = Date.now() - date.getTime()
  const diffMin = Math.floor(diffMs / 60000)
  if (diffMin < 1) {
    return 'ahora'
  }
  if (diffMin < 60) {
    return `hace ${diffMin} min`
  }
  const diffHours = Math.floor(diffMin / 60)
  if (diffHours < 24) {
    return `hace ${diffHours} h`
  }
  const diffDays = Math.floor(diffHours / 24)
  return `hace ${diffDays} d`
}

function RecentDocumentShimmer() {
  return (
    <li className="flex items-stretch" aria-hidden="true">
      <div className="w-10 shrink-0" />
      <div className="flex min-w-0 flex-1 items-center gap-3 px-4 py-3">
        <div className="h-4 min-w-0 flex-1 animate-pulse rounded bg-zinc-200 dark:bg-zinc-700" />
        <div className="h-3 w-12 shrink-0 animate-pulse rounded bg-zinc-200 dark:bg-zinc-700" />
        <div className="h-3 w-16 shrink-0 animate-pulse rounded bg-zinc-200 dark:bg-zinc-700" />
      </div>
    </li>
  )
}

export function RecentDocumentsList({
  documents,
  skeletonCount = 0,
  isLoadingDetails = false,
  error = null,
  onRetry,
  onDelete,
}: RecentDocumentsListProps) {
  const { setDocument } = usePdfDocument()
  const navigate = useNavigate()

  async function openDocument(id: string) {
    try {
      const doc = await getDocument(id)
      setDocument(doc)
      navigate('/viewer')
    } catch {
      // ignore — list may be stale
    }
  }

  const showSection =
    skeletonCount > 0 || documents.length > 0 || Boolean(error)

  if (!showSection) {
    return null
  }

  const shimmerRows = isLoadingDetails
    ? Array.from({ length: skeletonCount }, (_, index) => (
        <RecentDocumentShimmer key={`shimmer-${index}`} />
      ))
    : []

  return (
    <section className="w-full">
      <h2 className="mb-3 text-sm font-semibold text-zinc-700 dark:text-zinc-300">
        Abiertos recientemente
      </h2>

      {error && (
        <div className="mb-3 text-center">
          <p className="text-sm text-amber-700 dark:text-amber-300">{error}</p>
          {onRetry && (
            <button
              type="button"
              onClick={onRetry}
              className="mt-2 text-sm font-medium text-violet-600 hover:underline dark:text-violet-400"
            >
              Reintentar
            </button>
          )}
        </div>
      )}

      {(isLoadingDetails || documents.length > 0) && (
        <ul
          className={`divide-y divide-zinc-200 overflow-y-auto overscroll-contain rounded-xl border border-zinc-200 bg-white dark:divide-zinc-700 dark:border-zinc-700 dark:bg-zinc-900 ${LIST_MAX_HEIGHT}`}
          aria-busy={isLoadingDetails}
          aria-label="Documentos abiertos recientemente"
        >
          {isLoadingDetails
            ? shimmerRows
            : documents.map((doc) => (
                <li key={doc.id} className="flex items-stretch">
                  {onDelete && (
                    <button
                      type="button"
                      aria-label={`Eliminar ${doc.filename}`}
                      onClick={() => void onDelete(doc.id)}
                      className="flex shrink-0 items-center px-3 text-zinc-400 transition-colors hover:bg-red-50 hover:text-red-600 dark:hover:bg-red-950/40 dark:hover:text-red-400"
                    >
                      <span aria-hidden="true" className="text-lg leading-none">
                        ×
                      </span>
                    </button>
                  )}
                  <button
                    type="button"
                    onClick={() => void openDocument(doc.id)}
                    className="flex min-w-0 flex-1 items-center gap-3 px-4 py-3 text-left transition-colors hover:bg-zinc-50 dark:hover:bg-zinc-800"
                  >
                    <span className="min-w-0 flex-1 truncate text-sm font-medium text-zinc-900 dark:text-zinc-100">
                      {doc.filename}
                    </span>
                    <span className="shrink-0 text-xs tabular-nums text-zinc-500 dark:text-zinc-400">
                      {doc.pageActual}
                      {doc.pageCount ? ` / ${doc.pageCount}` : ''}
                    </span>
                    {doc.lastOpenedAt && (
                      <span className="shrink-0 text-xs text-zinc-400 dark:text-zinc-500">
                        {formatRelative(doc.lastOpenedAt)}
                      </span>
                    )}
                  </button>
                </li>
              ))}
        </ul>
      )}
    </section>
  )
}
