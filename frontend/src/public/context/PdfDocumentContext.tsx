import { createContext, useCallback, useState } from 'react'
import type { ReactNode } from 'react'

import type { I_PdfDocument } from '../../classes/I_PdfDocument'

export interface PdfDocumentContextValue {
  document: I_PdfDocument | null
  setDocument: (document: I_PdfDocument) => void
  clearDocument: () => void
}

export const PdfDocumentContext = createContext<PdfDocumentContextValue | null>(
  null,
)

export function PdfDocumentProvider({ children }: { children: ReactNode }) {
  const [document, setDocumentState] = useState<I_PdfDocument | null>(null)

  const setDocument = useCallback((next: I_PdfDocument) => {
    setDocumentState(next)
  }, [])

  const clearDocument = useCallback(() => {
    setDocumentState(null)
  }, [])

  return (
    <PdfDocumentContext.Provider
      value={{ document, setDocument, clearDocument }}
    >
      {children}
    </PdfDocumentContext.Provider>
  )
}
