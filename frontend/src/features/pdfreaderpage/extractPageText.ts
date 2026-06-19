import type { PDFDocumentProxy } from 'pdfjs-dist'

import { normalizeParagraphChunks } from '../../classes/paragraphSplitter.ts'

const Y_TOLERANCE = 4
const PARAGRAPH_GAP_FACTOR = 1.5

interface PdfTextItem {
  str: string
  transform: number[]
  height?: number
}

interface TextLine {
  y: number
  height: number
  parts: string[]
}

function isTextItem(item: unknown): item is PdfTextItem {
  return (
    typeof item === 'object' &&
    item !== null &&
    'str' in item &&
    'transform' in item &&
    Array.isArray((item as PdfTextItem).transform)
  )
}

function groupItemsIntoLines(items: PdfTextItem[]): TextLine[] {
  const lines: TextLine[] = []

  for (const item of items) {
    const y = item.transform[5]
    const height = item.height ?? Math.abs(item.transform[3]) ?? 12
    const existing = lines.find((line) => Math.abs(line.y - y) <= Y_TOLERANCE)

    if (existing) {
      existing.parts.push(item.str)
      existing.height = Math.max(existing.height, height)
    } else {
      lines.push({ y, height, parts: [item.str] })
    }
  }

  return lines
}

function groupLinesIntoParagraphs(lines: TextLine[]): string[] {
  const sorted = [...lines].sort((a, b) => b.y - a.y)
  const paragraphs: string[] = []
  let currentParts: string[] = []
  let previousLine: TextLine | null = null

  for (const line of sorted) {
    const text = line.parts.join(' ').trim()
    if (!text) {
      continue
    }

    if (previousLine) {
      const gap = Math.abs(previousLine.y - line.y)
      const threshold =
        Math.max(previousLine.height, line.height) * PARAGRAPH_GAP_FACTOR

      if (gap > threshold && currentParts.length > 0) {
        paragraphs.push(currentParts.join(' '))
        currentParts = []
      }
    }

    currentParts.push(text)
    previousLine = line
  }

  if (currentParts.length > 0) {
    paragraphs.push(currentParts.join(' '))
  }

  return paragraphs
}

export async function getPageParagraphs(
  pdf: PDFDocumentProxy,
  pageNumber: number,
): Promise<string[]> {
  const page = await pdf.getPage(pageNumber)
  const content = await page.getTextContent()
  const items: PdfTextItem[] = []
  for (const item of content.items) {
    if (isTextItem(item)) {
      items.push(item)
    }
  }

  if (items.length === 0) {
    return []
  }

  const lines = groupItemsIntoLines(items)
  const paragraphs = groupLinesIntoParagraphs(lines)
  return normalizeParagraphChunks(paragraphs)
}
