import type { I_Capitulo } from '../../classes/I_Capitulo.ts'
import type { I_ResumenesMetadata } from '../../classes/I_ResumenCapitulo.ts'
import { getPreviousChapterSummaries } from './summaryUtils.ts'

interface SummaryPanelProps {
  chapters: I_Capitulo[]
  resumenes: I_ResumenesMetadata
  pageNumber: number
  isOpen: boolean
  onClose: () => void
  onRetry?: () => void
}

export function SummaryPanel({
  chapters,
  resumenes,
  pageNumber,
  isOpen,
  onClose,
  onRetry,
}: SummaryPanelProps) {
  if (!isOpen) {
    return null
  }

  const previousSummaries = getPreviousChapterSummaries(
    chapters,
    resumenes.items,
    pageNumber,
  )

  return (
    <>
      <button
        type="button"
        className="fixed inset-0 z-40"
        aria-label="Cerrar panel de resúmenes"
        onClick={onClose}
      />
      <div
        className="fixed top-14 right-4 z-50 max-h-[min(24rem,calc(100vh-5rem))] w-80 overflow-y-auto rounded-xl border border-zinc-200 bg-white shadow-xl dark:border-zinc-700 dark:bg-zinc-900 sm:w-96"
        role="region"
        aria-label="Resúmenes de capítulos anteriores"
      >
        <div className="sticky top-0 border-b border-zinc-200 bg-white px-3 py-2 text-xs font-semibold uppercase tracking-wide text-zinc-500 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-400">
          Resumen
        </div>
        <div className="p-3">
          {resumenes.status === 'loading' && (
            <p className="text-sm text-zinc-600 dark:text-zinc-300">
              Generando resúmenes… {Math.round(resumenes.percent)}%
            </p>
          )}

          {resumenes.status === 'failed' && (
            <div className="space-y-2">
              <p className="text-sm text-red-600 dark:text-red-400">
                {resumenes.error ?? 'No se pudieron generar los resúmenes.'}
              </p>
              {onRetry && (
                <button
                  type="button"
                  onClick={onRetry}
                  className="rounded-md bg-violet-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-violet-700"
                >
                  Reintentar
                </button>
              )}
            </div>
          )}

          {resumenes.status === 'ready' && previousSummaries.length === 0 && (
            <p className="text-sm text-zinc-600 dark:text-zinc-300">
              Aún no hay resúmenes de capítulos anteriores.
            </p>
          )}

          {previousSummaries.length > 0 && (
            <ul className="space-y-4">
              {previousSummaries.map((item) => (
                <li key={item.numero}>
                  <p className="text-sm font-medium text-zinc-800 dark:text-zinc-100">
                    {item.numero}. {item.nombre}
                  </p>
                  <p className="mt-1 text-sm leading-relaxed text-zinc-600 dark:text-zinc-300">
                    {item.resumen}
                  </p>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </>
  )
}
