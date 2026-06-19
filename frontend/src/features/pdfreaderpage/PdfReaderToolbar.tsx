interface PdfReaderToolbarProps {
  fileName: string
  onChangePdf: () => void
  chaptersButtonDisabled?: boolean
  chaptersLoading?: boolean
  onToggleChapters?: () => void
  isPageTextReady?: boolean
  isTextOverlayOpen?: boolean
  onToggleTextOverlay?: () => void
  summaryButtonDisabled?: boolean
  summariesLoading?: boolean
  summaryProgressPercent?: number | null
  isSummaryPanelOpen?: boolean
  onToggleSummary?: () => void
}

const iconBtn =
  'rounded-md px-2 py-1.5 text-sm font-medium text-violet-600 transition-colors hover:bg-zinc-100 disabled:opacity-40 disabled:hover:bg-transparent dark:hover:bg-zinc-800'

export function PdfReaderToolbar({
  fileName,
  onChangePdf,
  chaptersButtonDisabled = true,
  chaptersLoading = false,
  onToggleChapters,
  isPageTextReady = false,
  isTextOverlayOpen = false,
  onToggleTextOverlay,
  summaryButtonDisabled = true,
  summariesLoading = false,
  summaryProgressPercent = null,
  isSummaryPanelOpen = false,
  onToggleSummary,
}: PdfReaderToolbarProps) {
  return (
    <header className="flex items-center justify-between gap-2 border-b border-zinc-200 bg-white px-4 py-3 dark:border-zinc-700 dark:bg-zinc-900">
      <p className="min-w-0 flex-1 truncate text-sm font-medium text-zinc-800 dark:text-zinc-100">
        {fileName}
      </p>
      <div className="flex shrink-0 items-center gap-1">
        <button
          type="button"
          onClick={onToggleChapters}
          disabled={chaptersButtonDisabled}
          className={`${iconBtn} flex items-center justify-center`}
          aria-label={
            chaptersLoading ? 'Detectando capítulos…' : 'Ver capítulos'
          }
          title={
            chaptersLoading
              ? 'Detectando capítulos…'
              : chaptersButtonDisabled
                ? 'Capítulos no disponibles'
                : 'Capítulos'
          }
        >
          <svg
            xmlns="http://www.w3.org/2000/svg"
            viewBox="0 0 24 24"
            fill="currentColor"
            className="h-5 w-5"
            aria-hidden
          >
            <path
              fillRule="evenodd"
              d="M3 6.75A.75.75 0 013.75 6h16.5a.75.75 0 010 1.5H3.75A.75.75 0 013 6.75zm0 5.25a.75.75 0 01.75-.75h16.5a.75.75 0 010 1.5H3.75a.75.75 0 01-.75-.75zm0 5.25a.75.75 0 01.75-.75h16.5a.75.75 0 010 1.5H3.75a.75.75 0 01-.75-.75z"
              clipRule="evenodd"
            />
          </svg>
        </button>

        <button
          type="button"
          onClick={onToggleTextOverlay}
          disabled={!isPageTextReady}
          className={`${iconBtn} flex items-center justify-center ${
            isTextOverlayOpen
              ? 'bg-violet-600 text-white hover:bg-violet-700 dark:hover:bg-violet-700'
              : ''
          }`}
          aria-label={
            isTextOverlayOpen ? 'Ocultar texto extraído' : 'Ver texto extraído'
          }
          aria-pressed={isTextOverlayOpen}
          title={
            !isPageTextReady
              ? 'Texto de página no disponible'
              : isTextOverlayOpen
                ? 'Ocultar texto'
                : 'Ver texto extraído'
          }
        >
          <svg
            xmlns="http://www.w3.org/2000/svg"
            viewBox="0 0 24 24"
            fill="currentColor"
            className="h-5 w-5"
            aria-hidden
          >
            <path d="M12 15a3 3 0 100-6 3 3 0 000 6z" />
            <path
              fillRule="evenodd"
              d="M1.323 11.447C2.811 6.976 7.028 3.75 12.001 3.75c4.97 0 9.185 3.223 10.675 7.69.12.362.12.752 0 1.113-1.487 4.471-5.705 7.697-10.677 7.697-4.97 0-9.186-3.223-10.675-7.69a1.762 1.762 0 010-1.113zM17.25 12a5.25 5.25 0 11-10.5 0 5.25 5.25 0 0110.5 0z"
              clipRule="evenodd"
            />
          </svg>
        </button>

        <button
          type="button"
          onClick={onToggleSummary}
          disabled={summaryButtonDisabled}
          className={`${iconBtn} flex items-center justify-center ${
            isSummaryPanelOpen
              ? 'bg-violet-600 text-white hover:bg-violet-700 dark:hover:bg-violet-700'
              : ''
          }`}
          aria-label={
            summariesLoading
              ? `Generando resúmenes… ${summaryProgressPercent ?? 0}%`
              : 'Ver resúmenes'
          }
          aria-pressed={isSummaryPanelOpen}
          title={
            summariesLoading
              ? `Generando resúmenes… ${summaryProgressPercent ?? 0}%`
              : summaryButtonDisabled
                ? 'Resúmenes no disponibles'
                : 'Resumen'
          }
        >
          <span className="relative flex h-5 w-5 items-center justify-center">
            <svg
              xmlns="http://www.w3.org/2000/svg"
              viewBox="0 0 24 24"
              fill="currentColor"
              className={`h-5 w-5 ${summariesLoading ? 'opacity-25' : ''}`}
              aria-hidden
            >
              <path
                fillRule="evenodd"
                d="M5.625 1.5H9a3.75 3.75 0 013.75 3.75v1.875c0 1.036.84 1.875 1.875 1.875H16.5a3.75 3.75 0 013.75 3.75v7.875c0 1.035-.84 1.875-1.875 1.875H5.625a1.875 1.875 0 01-1.875-1.875V3.375c0-1.036.84-1.875 1.875-1.875zm6.61 4.495a.75.75 0 10-1.06-1.06l-3 3a.75.75 0 001.06 1.06l3-3zm-2.655 3.128a.75.75 0 10-1.06-1.06l-1.5 1.5a.75.75 0 001.06 1.06l1.5-1.5zM7.5 14.25a.75.75 0 01.75-.75h7.5a.75.75 0 010 1.5h-7.5a.75.75 0 01-.75-.75zm.75 2.25a.75.75 0 000 1.5H12a.75.75 0 000-1.5H8.25z"
                clipRule="evenodd"
              />
            </svg>
            {summariesLoading && summaryProgressPercent !== null && (
              <span className="absolute inset-0 flex items-center justify-center text-[8px] font-bold leading-none tabular-nums">
                {summaryProgressPercent}%
              </span>
            )}
          </span>
        </button>

        <button
          type="button"
          onClick={onChangePdf}
          className="ml-1 shrink-0 rounded-md border border-zinc-300 px-3 py-1.5 text-sm font-medium text-zinc-700 transition-colors hover:bg-zinc-100 dark:border-zinc-600 dark:text-zinc-200 dark:hover:bg-zinc-800"
        >
          Cambiar PDF
        </button>
      </div>
    </header>
  )
}
