import { useCallback, useEffect, useRef, useState } from 'react'

import { getPageText, waitForPageParagraphs, waitForPageParagraphsForReading } from '../../api/pageTextApi.ts'

interface PageSlot {
  page: number
  paragraphs: string[]
}

interface UsePageTextWindowOptions {
  documentId: string
  pageNumber: number
  numPages: number
}

export function usePageTextWindow({
  documentId,
  pageNumber,
  numPages,
}: UsePageTextWindowOptions) {
  const [isCurrentLoading, setIsCurrentLoading] = useState(false)
  const [currentParagraphs, setCurrentParagraphs] = useState<string[]>([])
  const [isPageTextReady, setIsPageTextReady] = useState(false)
  const currentRef = useRef<PageSlot | null>(null)
  const nextRef = useRef<PageSlot | null>(null)
  const inflightRef = useRef<Map<number, Promise<string[]>>>(new Map())
  const readingInflightRef = useRef<Map<number, Promise<string[]>>>(new Map())
  const generationRef = useRef(0)

  const loadPage = useCallback(
    async (page: number, markCurrentLoading: boolean): Promise<string[]> => {
      if (page < 1 || page > numPages) {
        return []
      }

      if (currentRef.current?.page === page) {
        return currentRef.current.paragraphs
      }
      if (nextRef.current?.page === page) {
        return nextRef.current.paragraphs
      }

      const loadPromise =
        inflightRef.current.get(page) ??
        (() => {
          const promise = waitForPageParagraphs(documentId, page)
          inflightRef.current.set(page, promise)
          void promise.finally(() => {
            inflightRef.current.delete(page)
          })
          return promise
        })()

      if (markCurrentLoading) {
        setIsCurrentLoading(true)
      }

      try {
        return await loadPromise
      } finally {
        if (markCurrentLoading) {
          setIsCurrentLoading(false)
        }
      }
    },
    [documentId, numPages],
  )

  const syncWindow = useCallback(
    async (targetPage: number, windowGeneration: number) => {
      if (targetPage < 1 || targetPage > numPages) {
        currentRef.current = null
        nextRef.current = null
        setCurrentParagraphs([])
        setIsPageTextReady(false)
        return
      }

      let currentParagraphs: string[]
      if (nextRef.current?.page === targetPage) {
        currentParagraphs = nextRef.current.paragraphs
        nextRef.current = null
      } else if (currentRef.current?.page === targetPage) {
        currentParagraphs = currentRef.current.paragraphs
      } else {
        currentParagraphs = await loadPage(targetPage, true)
        if (windowGeneration !== generationRef.current) {
          return
        }
      }

      currentRef.current = { page: targetPage, paragraphs: currentParagraphs }
      setCurrentParagraphs(currentParagraphs)
      setIsPageTextReady(true)

      const nextPage = targetPage + 1
      if (nextPage <= numPages) {
        void loadPage(nextPage, false).then(async () => {
          if (windowGeneration !== generationRef.current) {
            return
          }
          const nextMeta = await getPageText(documentId, nextPage)
          if (
            windowGeneration !== generationRef.current ||
            nextMeta.status !== 'ready'
          ) {
            return
          }
          nextRef.current = { page: nextPage, paragraphs: nextMeta.paragraphs }

          const currentMeta = await getPageText(documentId, targetPage)
          if (
            windowGeneration !== generationRef.current ||
            currentMeta.status !== 'ready'
          ) {
            return
          }
          currentRef.current = {
            page: targetPage,
            paragraphs: currentMeta.paragraphs,
          }
          setCurrentParagraphs(currentMeta.paragraphs)
        })
      } else {
        nextRef.current = null
      }
    },
    [loadPage, numPages],
  )

  useEffect(() => {
    if (numPages < 1) {
      return
    }

    generationRef.current += 1
    const windowGeneration = generationRef.current

    if (nextRef.current && nextRef.current.page !== pageNumber) {
      if (nextRef.current.page < pageNumber - 1 || nextRef.current.page > pageNumber + 1) {
        nextRef.current = null
      }
    }

    if (currentRef.current && currentRef.current.page < pageNumber - 1) {
      currentRef.current = null
    }

    if (currentRef.current?.page === pageNumber) {
      setCurrentParagraphs(currentRef.current.paragraphs)
      setIsPageTextReady(true)
    } else if (nextRef.current?.page === pageNumber) {
      setCurrentParagraphs(nextRef.current.paragraphs)
      setIsPageTextReady(true)
    } else {
      setIsPageTextReady(false)
    }

    void syncWindow(pageNumber, windowGeneration)
  }, [documentId, pageNumber, numPages, syncWindow])

  const getParagraphs = useCallback(
    async (page: number): Promise<string[]> => {
      if (currentRef.current?.page === page) {
        return currentRef.current.paragraphs
      }
      if (nextRef.current?.page === page) {
        return nextRef.current.paragraphs
      }
      const paragraphs = await loadPage(page, page === pageNumber)
      if (page === pageNumber) {
        currentRef.current = { page, paragraphs }
      } else if (page === pageNumber + 1) {
        nextRef.current = { page, paragraphs }
      }
      return paragraphs
    },
    [loadPage, pageNumber],
  )

  const getParagraphsForReading = useCallback(
    async (page: number): Promise<string[]> => {
      if (page < 1 || page > numPages) {
        return []
      }
      if (currentRef.current?.page === page) {
        return currentRef.current.paragraphs
      }
      if (nextRef.current?.page === page) {
        return nextRef.current.paragraphs
      }

      const loadPromise =
        readingInflightRef.current.get(page) ??
        (() => {
          const promise = waitForPageParagraphsForReading(documentId, page)
          readingInflightRef.current.set(page, promise)
          void promise.finally(() => {
            readingInflightRef.current.delete(page)
          })
          return promise
        })()

      return loadPromise
    },
    [documentId, numPages],
  )

  const invalidate = useCallback(() => {
    generationRef.current += 1
    currentRef.current = null
    nextRef.current = null
    inflightRef.current.clear()
    readingInflightRef.current.clear()
    setCurrentParagraphs([])
    setIsPageTextReady(false)
  }, [])

  return {
    getParagraphs,
    getParagraphsForReading,
    isCurrentLoading,
    currentParagraphs,
    isPageTextReady,
    invalidate,
  }
}
