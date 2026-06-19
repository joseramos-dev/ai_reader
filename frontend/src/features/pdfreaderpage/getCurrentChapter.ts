import type { I_Capitulo } from '../../classes/I_Capitulo.ts'

export function getCurrentChapter(
  chapters: I_Capitulo[],
  pageNumber: number,
): I_Capitulo | null {
  return chapters.reduce<I_Capitulo | null>(
    (current, chapter) => (chapter.pagina <= pageNumber ? chapter : current),
    null,
  )
}
