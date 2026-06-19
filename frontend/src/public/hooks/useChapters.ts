import { useCallback, useEffect, useRef, useState } from 'react'

import type { PDFDocumentProxy } from 'pdfjs-dist'

import {
  detectChapters,
  putOutlineChapters,
  refreshDocumentCapitulos,
} from '../../api/documentsApi.ts'
import type { I_Capitulo, I_CapitulosMetadata } from '../../classes/I_Capitulo.ts'
import { extractPdfOutline } from '../../features/pdfreaderpage/extractPdfOutline.ts'

const POLL_MS = 2000

const EMPTY_CAPITULOS: I_CapitulosMetadata = {
  items: [],
  status: 'idle',
  source: null,
  error: null,
}

interface UseChaptersOptions {
  documentId: string
  pdfRef: React.RefObject<PDFDocumentProxy | null>
  initialCapitulos?: I_CapitulosMetadata
  textProcessingComplete?: boolean
}

export function useChapters({
  documentId,
  pdfRef,
  initialCapitulos,
  textProcessingComplete = false,
}: UseChaptersOptions) {
  const [capitulos, setCapitulos] = useState<I_CapitulosMetadata>(
    initialCapitulos ?? EMPTY_CAPITULOS,
  )
  const [isMenuOpen, setIsMenuOpen] = useState(false)
  const startedRef = useRef(false)
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const capitulosRef = useRef(capitulos)

  useEffect(() => {
    capitulosRef.current = capitulos
  }, [capitulos])

  useEffect(() => {
    startedRef.current = false
  }, [documentId])

  const isLoading = capitulos.status === 'loading'
  const chapters: I_Capitulo[] = capitulos.items

  const stopPolling = useCallback(() => {
    if (pollRef.current) {
      clearInterval(pollRef.current)
      pollRef.current = null
    }
  }, [])

  const startPolling = useCallback(() => {
    stopPolling()
    pollRef.current = setInterval(async () => {
      try {
        const next = await refreshDocumentCapitulos(documentId)
        setCapitulos(next)
        if (next.status !== 'loading') {
          stopPolling()
        }
      } catch {
        stopPolling()
      }
    }, POLL_MS)
  }, [documentId, stopPolling])

  const closeMenu = useCallback(() => {
    setIsMenuOpen(false)
  }, [])

  const openMenu = useCallback(() => {
    if (!isLoading && capitulos.status === 'ready' && chapters.length > 0) {
      setIsMenuOpen(true)
    }
  }, [chapters.length, capitulos.status, isLoading])

  useEffect(() => {
    if (isLoading && isMenuOpen) {
      setIsMenuOpen(false)
    }
  }, [isLoading, isMenuOpen])

  useEffect(() => {
    return () => stopPolling()
  }, [stopPolling])

  useEffect(() => {
    if (initialCapitulos) {
      setCapitulos(initialCapitulos)
    }
  }, [initialCapitulos])

  useEffect(() => {
    if (initialCapitulos?.status === 'loading') {
      startPolling()
    }
  }, [documentId, initialCapitulos?.status, startPolling])

  useEffect(() => {
    if (!textProcessingComplete) {
      return
    }

    const current = capitulosRef.current
    if (current.status === 'ready' && current.items.length > 0) {
      return
    }
    if (current.source === 'outline') {
      return
    }
    if (current.status === 'loading') {
      startPolling()
      return
    }

    void refreshDocumentCapitulos(documentId)
      .then((next) => {
        setCapitulos(next)
        if (next.status === 'loading') {
          startPolling()
        }
      })
      .catch((err) => {
        console.error(err)
      })
  }, [documentId, startPolling, textProcessingComplete])

  const runDetection = useCallback(async () => {
    const pdf = pdfRef.current
    if (!pdf || startedRef.current) {
      return
    }
    startedRef.current = true

    const current = capitulosRef.current
    if (current.status === 'ready' && current.items.length > 0) {
      return
    }
    if (current.status === 'loading') {
      startPolling()
      return
    }
    if (current.status === 'failed') {
      return
    }

    try {
      const outline = await extractPdfOutline(pdf)
      if (outline.length > 0) {
        const saved = await putOutlineChapters(documentId, outline)
        setCapitulos(saved)
        return
      }

      const meta = await detectChapters(documentId)
      setCapitulos(meta)
      if (meta.status === 'loading') {
        startPolling()
      }
    } catch (err) {
      console.error(err)
      setCapitulos((prev) => ({
        ...prev,
        status: 'failed',
        error: 'No se pudieron detectar los capítulos.',
      }))
    }
  }, [documentId, pdfRef, startPolling])

  const onPdfLoaded = useCallback(() => {
    void runDetection()
  }, [runDetection])

  return {
    chapters,
    capitulos,
    isLoading,
    isMenuOpen,
    openMenu,
    closeMenu,
    onPdfLoaded,
    chaptersReady:
      capitulos.status === 'ready' && capitulos.items.length > 0,
    chaptersButtonDisabled:
      isLoading || capitulos.status !== 'ready' || chapters.length === 0,
  }
}
