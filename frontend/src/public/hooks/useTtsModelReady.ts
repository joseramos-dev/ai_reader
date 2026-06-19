import { useEffect, useState } from 'react'

import { getTtsHealth } from '../../api/ttsApi.ts'

const POLL_INTERVAL_MS = 500

export function useTtsModelReady() {
  const [isModelLoading, setIsModelLoading] = useState(true)
  const [activeEngine, setActiveEngine] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    let intervalId: number | undefined

    async function check() {
      try {
        const health = await getTtsHealth()
        if (cancelled) {
          return
        }
        setActiveEngine(health.active_engine)
        const engineReady =
          health.active_engine !== null &&
          !health.loading &&
          health.memory.active_engine_loaded

        if (engineReady) {
          setIsModelLoading(false)
          if (intervalId !== undefined) {
            clearInterval(intervalId)
          }
        } else if (health.loading) {
          setIsModelLoading(true)
        }
      } catch {
        // Backend aún no disponible o arrancando; seguir sondeando.
      }
    }

    void check()
    intervalId = window.setInterval(check, POLL_INTERVAL_MS)

    return () => {
      cancelled = true
      if (intervalId !== undefined) {
        clearInterval(intervalId)
      }
    }
  }, [])

  return { isModelLoading, activeEngine }
}
