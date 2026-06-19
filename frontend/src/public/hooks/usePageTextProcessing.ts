import { useEffect, useState } from 'react'

import { getPageTextStatus } from '../../api/pageTextApi.ts'
import type { I_PageTextProcessingStatus } from '../../classes/I_PageText.ts'

const POLL_INTERVAL_MS = 1500

interface UsePageTextProcessingOptions {
  documentId: string
  enabled?: boolean
}

export function usePageTextProcessing({
  documentId,
  enabled = true,
}: UsePageTextProcessingOptions) {
  const [status, setStatus] = useState<I_PageTextProcessingStatus | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!enabled || !documentId) {
      return
    }

    let cancelled = false

    async function poll() {
      try {
        const next = await getPageTextStatus(documentId)
        if (cancelled) {
          return
        }
        setStatus(next)
        setError(null)
        if (!next.is_complete) {
          window.setTimeout(() => {
            void poll()
          }, POLL_INTERVAL_MS)
        }
      } catch {
        if (!cancelled) {
          setError('No se pudo comprobar el progreso del texto.')
        }
      }
    }

    void poll()

    return () => {
      cancelled = true
    }
  }, [documentId, enabled])

  const isProcessing = status !== null && !status.is_complete && status.loading > 0

  return {
    status,
    error,
    isProcessing,
  }
}
