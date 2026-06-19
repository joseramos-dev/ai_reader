import type { PDFDocumentProxy } from 'pdfjs-dist'

import type { I_CapituloInput } from '../../classes/I_Capitulo.ts'

interface OutlineItem {
  title: string
  dest: unknown
  items?: OutlineItem[]
}

async function resolveDestToPage(
  pdf: PDFDocumentProxy,
  dest: unknown,
): Promise<number | null> {
  if (!dest) {
    return null
  }
  try {
    let explicitDest: unknown = dest
    if (typeof dest === 'string') {
      explicitDest = await pdf.getDestination(dest)
    }
    if (!Array.isArray(explicitDest) || explicitDest.length === 0) {
      return null
    }
    const ref = explicitDest[0]
    const pageIndex = await pdf.getPageIndex(ref)
    return pageIndex + 1
  } catch {
    return null
  }
}

async function flattenOutline(
  pdf: PDFDocumentProxy,
  items: OutlineItem[],
  acc: I_CapituloInput[],
): Promise<void> {
  for (const item of items) {
    const title = item.title?.trim()
    if (title) {
      const page = await resolveDestToPage(pdf, item.dest)
      if (page !== null) {
        acc.push({ nombre: title, pagina: page })
      }
    }
    if (item.items?.length) {
      await flattenOutline(pdf, item.items, acc)
    }
  }
}

export async function extractPdfOutline(
  pdf: PDFDocumentProxy,
): Promise<I_CapituloInput[]> {
  let outline: OutlineItem[] | null
  try {
    outline = (await pdf.getOutline()) as OutlineItem[] | null
  } catch {
    return []
  }
  if (!outline?.length) {
    return []
  }
  const result: I_CapituloInput[] = []
  await flattenOutline(pdf, outline, result)
  const seen = new Set<string>()
  return result.filter((item) => {
    const key = `${item.pagina}:${item.nombre}`
    if (seen.has(key)) {
      return false
    }
    seen.add(key)
    return true
  })
}
