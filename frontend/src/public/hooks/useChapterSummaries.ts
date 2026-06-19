import { useCallback, useEffect, useRef, useState } from 'react'

import { refreshDocumentResumenes, generateSummaries } from '../../api/documentsApi.ts'
import type { I_ResumenesMetadata } from '../../classes/I_ResumenCapitulo.ts'

const POLL_MS = 2000

const EMPTY_RESUMENES: I_ResumenesMetadata = {
  items: [],
  status: 'idle',
  error: null,
  total: 0,
  percent: 0,
}

interface UseChapterSummariesOptions {
  documentId: string
  initialResumenes?: I_ResumenesMetadata
  chaptersReady: boolean
  textProcessingComplete: boolean
}

export function useChapterSummaries({
  documentId,
  initialResumenes,
  chaptersReady,
  textProcessingComplete,
}: UseChapterSummariesOptions) {
  const [resumenes, setResumenes] = useState<I_ResumenesMetadata>(
    initialResumenes ?? EMPTY_RESUMENES,
  )
  const [isPanelOpen, setIsPanelOpen] = useState(false)
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const resumenesRef = useRef(resumenes)
  const autoRetriedRef = useRef(false)

  useEffect(() => {
    resumenesRef.current = resumenes
  }, [resumenes])

  const isLoading = resumenes.status === 'loading'

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
        const next = await refreshDocumentResumenes(documentId)
        setResumenes(next)
        if (next.status === 'ready' || next.status === 'failed') {
          stopPolling()
        }
      } catch {
        stopPolling()
      }
    }, POLL_MS)
  }, [documentId, stopPolling])

  useEffect(() => {
    return () => stopPolling()
  }, [stopPolling])

  useEffect(() => {
    if (initialResumenes) {
      setResumenes(initialResumenes)
    }
  }, [initialResumenes])

  useEffect(() => {
    if (initialResumenes?.status === 'loading') {
      startPolling()
    }
  }, [documentId, initialResumenes?.status, startPolling])

  useEffect(() => {
    if (!chaptersReady || !textProcessingComplete) {
      return
    }

    const current = resumenesRef.current
    if (current.status === 'ready' || current.status === 'failed') {
      return
    }

    void refreshDocumentResumenes(documentId)
      .then((next) => {
        setResumenes(next)
      })
      .catch((err) => {
        console.error(err)
      })

    startPolling()
  }, [chaptersReady, documentId, startPolling, textProcessingComplete])

  const closePanel = useCallback(() => {
    setIsPanelOpen(false)
  }, [])

  const togglePanel = useCallback(() => {
    if (!chaptersReady || !textProcessingComplete) {
      return
    }
    if (resumenes.status === 'idle') {
      return
    }
    setIsPanelOpen((open) => !open)
  }, [chaptersReady, resumenes.status, textProcessingComplete])

  const retryGeneration = useCallback(async () => {
    try {
      const next = await generateSummaries(documentId)
      setResumenes(next)
      startPolling()
    } catch (err) {
      console.error(err)
      try {
        const refreshed = await refreshDocumentResumenes(documentId)
        setResumenes(refreshed)
      } catch {
        // keep current state
      }
    }
  }, [documentId, startPolling])

  useEffect(() => {
    autoRetriedRef.current = false
  }, [documentId])

  useEffect(() => {
    if (!isPanelOpen) {
      autoRetriedRef.current = false
      return
    }
    if (resumenes.status !== 'failed' || autoRetriedRef.current) {
      return
    }
    autoRetriedRef.current = true
    void retryGeneration()
  }, [isPanelOpen, resumenes.status, retryGeneration])

  return {
    resumenes,
    isLoading,
    isPanelOpen,
    togglePanel,
    closePanel,
    retryGeneration,
    summaryProgressPercent: isLoading ? Math.round(resumenes.percent) : null,
    summaryButtonDisabled:
      !chaptersReady ||
      !textProcessingComplete ||
      resumenes.status === 'idle',
  }
}
