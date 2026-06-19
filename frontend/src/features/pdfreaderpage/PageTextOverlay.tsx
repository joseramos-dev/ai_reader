interface PageTextOverlayProps {
  paragraphs: string[]
  isLoading?: boolean
}

export function PageTextOverlay({
  paragraphs,
  isLoading = false,
}: PageTextOverlayProps) {
  return (
    <div
      className="absolute inset-0 z-10 overflow-auto bg-white/95 p-6 dark:bg-zinc-900/95"
      aria-live="polite"
    >
      {isLoading ? (
        <p className="text-sm text-zinc-500 dark:text-zinc-400">
          Cargando texto…
        </p>
      ) : paragraphs.length === 0 ? (
        <p className="text-sm italic text-zinc-500 dark:text-zinc-400">
          No hay texto en esta página.
        </p>
      ) : (
        <div className="space-y-4 text-left text-sm leading-relaxed text-zinc-800 dark:text-zinc-100">
          {paragraphs.map((paragraph, index) => (
            <p key={index}>{paragraph}</p>
          ))}
        </div>
      )}
    </div>
  )
}
