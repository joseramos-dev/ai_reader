import type { I_Capitulo } from '../../classes/I_Capitulo.ts'
import type { I_ResumenCapitulo } from '../../classes/I_ResumenCapitulo.ts'
import { getCurrentChapter } from './getCurrentChapter.ts'

export function getPreviousChapterSummaries(
  chapters: I_Capitulo[],
  summaries: I_ResumenCapitulo[],
  pageNumber: number,
): I_ResumenCapitulo[] {
  const current = getCurrentChapter(chapters, pageNumber)
  if (!current || current.numero <= 1) {
    return []
  }
  return summaries.filter((item) => item.numero < current.numero)
}
