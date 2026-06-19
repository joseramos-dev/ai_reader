import { useContext } from 'react'

import {
  PdfDocumentContext,
  type PdfDocumentContextValue,
} from '../context/PdfDocumentContext'

export function usePdfDocument(): PdfDocumentContextValue {
  const context = useContext(PdfDocumentContext)
  if (!context) {
    throw new Error('usePdfDocument must be used within a PdfDocumentProvider')
  }
  return context
}
