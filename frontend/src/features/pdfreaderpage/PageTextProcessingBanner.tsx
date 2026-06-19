interface PageTextProcessingBannerProps {
  ready: number
  total: number
  percent: number
  failed: number
}

export function PageTextProcessingBanner({
  ready,
  total,
  percent,
  failed,
}: PageTextProcessingBannerProps) {
  return (
    <div className="mx-auto mb-3 w-full max-w-xl rounded-lg border border-violet-200 bg-violet-50 px-4 py-3 dark:border-violet-900 dark:bg-violet-950/40">
      <div className="flex items-center justify-between gap-3 text-sm text-violet-900 dark:text-violet-100">
        <span>
          Preparando texto: {ready}/{total} páginas
        </span>
        <span className="tabular-nums">{percent.toFixed(0)}%</span>
      </div>
      <div
        className="mt-2 h-2 overflow-hidden rounded-full bg-violet-200 dark:bg-violet-900"
        role="progressbar"
        aria-valuenow={percent}
        aria-valuemin={0}
        aria-valuemax={100}
      >
        <div
          className="h-full rounded-full bg-violet-600 transition-all duration-300"
          style={{ width: `${percent}%` }}
        />
      </div>
      {failed > 0 && (
        <p className="mt-2 text-xs text-amber-700 dark:text-amber-300">
          {failed} página(s) con error durante la extracción.
        </p>
      )}
    </div>
  )
}
