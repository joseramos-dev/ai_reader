import type { I_Capitulo } from '../../classes/I_Capitulo.ts'

interface ChapterMenuProps {
  chapters: I_Capitulo[]
  isOpen: boolean
  onClose: () => void
  onSelectChapter: (page: number) => void
}

export function ChapterMenu({
  chapters,
  isOpen,
  onClose,
  onSelectChapter,
}: ChapterMenuProps) {
  if (!isOpen) {
    return null
  }

  return (
    <>
      <button
        type="button"
        className="fixed inset-0 z-40"
        aria-label="Cerrar menú de capítulos"
        onClick={onClose}
      />
      <div
        className="fixed top-14 left-4 z-50 max-h-64 w-72 overflow-y-auto rounded-xl border border-zinc-200 bg-white shadow-xl dark:border-zinc-700 dark:bg-zinc-900"
        role="menu"
      >
        <div className="sticky top-0 border-b border-zinc-200 bg-white px-3 py-2 text-xs font-semibold uppercase tracking-wide text-zinc-500 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-400">
          Capítulos
        </div>
        <ul className="py-1">
          {chapters.map((chapter) => (
            <li key={`${chapter.numero}-${chapter.pagina}`}>
              <button
                type="button"
                role="menuitem"
                className="flex w-full items-start gap-2 px-3 py-2 text-left text-sm text-zinc-800 hover:bg-zinc-100 dark:text-zinc-100 dark:hover:bg-zinc-800"
                onClick={() => {
                  onSelectChapter(chapter.pagina)
                  onClose()
                }}
              >
                <span className="shrink-0 tabular-nums text-zinc-500 dark:text-zinc-400">
                  {chapter.numero}.
                </span>
                <span className="min-w-0 flex-1 truncate">{chapter.nombre}</span>
                <span className="shrink-0 tabular-nums text-xs text-zinc-500 dark:text-zinc-400">
                  p.{chapter.pagina}
                </span>
              </button>
            </li>
          ))}
        </ul>
      </div>
    </>
  )
}
