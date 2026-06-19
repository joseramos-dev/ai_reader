import { useCallback, useEffect, useRef, useState } from 'react'

import type { PDFDocumentProxy } from 'pdfjs-dist'

import { TtsReadSocket } from '../../api/ttsReadSocket.ts'
import { invalidateChunkCache, prefetchPageChunks } from '../../classes/chunkingStrategy.ts'
import type { I_SelectedVoice } from '../../classes/I_TtsVoice.ts'

const MAX_PIPELINE_DEPTH = 9
const MAX_PLAYBACK_QUEUE = 4

interface PlaybackItem {
  buffer: AudioBuffer
  page: number
  isLastOfPage: boolean
}

interface ChunkMeta {
  page: number
  isLastOfPage: boolean
  producerGeneration: number
}

interface UseReadAloudOptions {
  pdfRef: React.RefObject<PDFDocumentProxy | null>
  documentId: string
  numPages: number
  selectedVoice: I_SelectedVoice
  speechSpeed: number
  getPageParagraphs: (page: number) => Promise<string[]>
  onReadingPageChange: (page: number) => void
}

export function useReadAloud({
  pdfRef,
  documentId,
  numPages,
  selectedVoice,
  speechSpeed,
  getPageParagraphs,
  onReadingPageChange,
}: UseReadAloudOptions) {
  const [isReading, setIsReading] = useState(false)
  const [isStarting, setIsStarting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const socketRef = useRef<TtsReadSocket | null>(null)
  const audioContextRef = useRef<AudioContext | null>(null)
  const playbackQueueRef = useRef<PlaybackItem[]>([])
  const prefetchBufferRef = useRef<PlaybackItem | null>(null)
  const pendingChunkMetaRef = useRef<ChunkMeta[]>([])
  const currentSourceRef = useRef<AudioBufferSourceNode | null>(null)
  const audioGenerationRef = useRef(0)
  const isReadingRef = useRef(false)
  const isTransitioningRef = useRef(false)
  const isPlayingRef = useRef(false)
  const selectedVoiceRef = useRef(selectedVoice)
  const speechSpeedRef = useRef(speechSpeed)
  const numPagesRef = useRef(numPages)
  const onReadingPageChangeRef = useRef(onReadingPageChange)
  const getPageParagraphsRef = useRef(getPageParagraphs)
  const documentIdRef = useRef(documentId)

  const sentChunksRef = useRef(0)
  const completedAudioRef = useRef(0)
  const serverTextDepthRef = useRef(0)
  const serverSynthInFlightRef = useRef(0)
  const serverPendingSendRef = useRef(0)
  const producerDoneRef = useRef(false)
  const producerGenerationRef = useRef(0)
  const readGenerationRef = useRef(0)
  const expectedAudioIndexRef = useRef(0)
  const capacityWaitersRef = useRef<Array<() => void>>([])

  useEffect(() => {
    selectedVoiceRef.current = selectedVoice
  }, [selectedVoice])

  useEffect(() => {
    speechSpeedRef.current = speechSpeed
    const source = currentSourceRef.current
    const ctx = audioContextRef.current
    if (source && ctx && ctx.state !== 'closed') {
      source.playbackRate.setValueAtTime(speechSpeed, ctx.currentTime)
    }
  }, [speechSpeed])

  useEffect(() => {
    numPagesRef.current = numPages
  }, [numPages])

  useEffect(() => {
    onReadingPageChangeRef.current = onReadingPageChange
  }, [onReadingPageChange])

  useEffect(() => {
    getPageParagraphsRef.current = getPageParagraphs
  }, [getPageParagraphs])

  useEffect(() => {
    documentIdRef.current = documentId
  }, [documentId])

  const getPlaybackPendingCount = useCallback(() => {
    return (
      playbackQueueRef.current.length +
      (prefetchBufferRef.current ? 1 : 0) +
      (isPlayingRef.current ? 1 : 0)
    )
  }, [])

  const getPipelineDepth = useCallback(() => {
    const serverSide =
      serverTextDepthRef.current +
      serverSynthInFlightRef.current +
      serverPendingSendRef.current
    const clientInFlight = Math.max(0, sentChunksRef.current - completedAudioRef.current)
    return Math.max(serverSide, clientInFlight) + getPlaybackPendingCount()
  }, [getPlaybackPendingCount])

  const notifyCapacityWaiters = useCallback(() => {
    const waiters = capacityWaitersRef.current.splice(0)
    for (const waiter of waiters) {
      waiter()
    }
  }, [])

  const waitForCapacity = useCallback(
    async (producerGeneration: number) => {
      while (
        isReadingRef.current &&
        producerGeneration === producerGenerationRef.current &&
        getPipelineDepth() >= MAX_PIPELINE_DEPTH
      ) {
        await new Promise<void>((resolve) => {
          capacityWaitersRef.current.push(resolve)
        })
      }
    },
    [getPipelineDepth],
  )

  const isProducerActive = useCallback((producerGeneration: number) => {
    return (
      isReadingRef.current &&
      producerGeneration === producerGenerationRef.current
    )
  }, [])

  const advancePastEmptyPages = useCallback(
    async (
      startPage: number,
      lastPage: number,
      producerGeneration: number,
      engine: string,
    ): Promise<{ page: number; chunks: string[] } | null> => {
      let page = startPage
      while (page <= lastPage && isProducerActive(producerGeneration)) {
        const chunks = await prefetchPageChunks(
          documentIdRef.current,
          page,
          engine,
          (p) => getPageParagraphsRef.current(p),
        )
        if (!isProducerActive(producerGeneration)) {
          return null
        }
        if (chunks.length > 0) {
          return { page, chunks }
        }
        if (page < lastPage) {
          onReadingPageChangeRef.current(page + 1)
        }
        page += 1
      }
      return null
    },
    [isProducerActive],
  )

  const maybeStopWhenDrained = useCallback(async () => {
    if (!producerDoneRef.current || !isReadingRef.current) {
      return
    }
    if (getPipelineDepth() > 0 || isPlayingRef.current) {
      return
    }
    isReadingRef.current = false
    setIsReading(false)
    socketRef.current?.stop()
    socketRef.current = null
    if (audioContextRef.current) {
      try {
        await audioContextRef.current.close()
      } catch {
        // already closed
      }
      audioContextRef.current = null
    }
  }, [getPipelineDepth])

  const clearLocalAudio = useCallback(
    (bumpGeneration: boolean) => {
      if (bumpGeneration) {
        audioGenerationRef.current += 1
      }
      try {
        currentSourceRef.current?.stop()
      } catch {
        // already stopped
      }
      currentSourceRef.current = null
      playbackQueueRef.current = []
      prefetchBufferRef.current = null
      pendingChunkMetaRef.current = []
      isPlayingRef.current = false
      sentChunksRef.current = 0
      completedAudioRef.current = 0
      serverTextDepthRef.current = 0
      serverSynthInFlightRef.current = 0
      serverPendingSendRef.current = 0
      producerDoneRef.current = false
      expectedAudioIndexRef.current = 0
      notifyCapacityWaiters()
    },
    [notifyCapacityWaiters],
  )

  const ensureAudioContext = useCallback(async () => {
    if (!audioContextRef.current || audioContextRef.current.state === 'closed') {
      audioContextRef.current = new AudioContext()
    }
    if (audioContextRef.current.state === 'suspended') {
      await audioContextRef.current.resume()
    }
    return audioContextRef.current
  }, [])

  const playNext = useCallback(
    (generation: number) => {
      if (generation !== audioGenerationRef.current || isPlayingRef.current) {
        return
      }

      let item = playbackQueueRef.current.shift()
      if (!item && prefetchBufferRef.current) {
        item = prefetchBufferRef.current
        prefetchBufferRef.current = null
      }
      if (!item) {
        void maybeStopWhenDrained()
        return
      }

      const ctx = audioContextRef.current
      if (!ctx || ctx.state === 'closed') {
        return
      }

      isPlayingRef.current = true
      const source = ctx.createBufferSource()
      source.buffer = item.buffer
      source.playbackRate.setValueAtTime(speechSpeedRef.current, ctx.currentTime)
      source.connect(ctx.destination)
      currentSourceRef.current = source
      source.onended = () => {
        isPlayingRef.current = false
        currentSourceRef.current = null
        notifyCapacityWaiters()

        if (
          item.isLastOfPage &&
          item.page < numPagesRef.current &&
          isReadingRef.current
        ) {
          onReadingPageChangeRef.current(item.page + 1)
        }

        playNext(generation)
        void maybeStopWhenDrained()
      }
      source.start()
    },
    [maybeStopWhenDrained, notifyCapacityWaiters],
  )

  const enqueueBuffer = useCallback(
    (
      buffer: AudioBuffer,
      meta: ChunkMeta,
      generation: number,
    ) => {
      if (generation !== audioGenerationRef.current) {
        return
      }

      const playbackItem: PlaybackItem = { buffer, ...meta }

      if (
        playbackQueueRef.current.length >= MAX_PLAYBACK_QUEUE &&
        !isPlayingRef.current
      ) {
        playbackQueueRef.current.shift()
      }

      if (
        playbackQueueRef.current.length >= MAX_PLAYBACK_QUEUE &&
        isPlayingRef.current &&
        !prefetchBufferRef.current
      ) {
        prefetchBufferRef.current = playbackItem
        notifyCapacityWaiters()
        return
      }

      playbackQueueRef.current.push(playbackItem)
      notifyCapacityWaiters()
      playNext(generation)
    },
    [notifyCapacityWaiters, playNext],
  )

  const stopReading = useCallback(async () => {
    producerGenerationRef.current += 1
    audioGenerationRef.current += 1
    clearLocalAudio(false)

    const socket = socketRef.current
    socketRef.current = null
    if (socket) {
      await socket.stop()
    }

    if (audioContextRef.current) {
      try {
        await audioContextRef.current.close()
      } catch {
        // already closed
      }
      audioContextRef.current = null
    }

    isReadingRef.current = false
    setIsReading(false)
    invalidateChunkCache()
  }, [clearLocalAudio])

  const runContinuousReading = useCallback(
    async (
      startPage: number,
      producerGeneration: number,
      options?: { endPage?: number; initialChunks?: string[] },
    ) => {
      const pdf = pdfRef.current
      if (!pdf) {
        setError('El PDF aún no está listo.')
        await stopReading()
        return
      }

      const { engine } = selectedVoiceRef.current
      const lastPage = options?.endPage ?? pdf.numPages
      const initialChunks = options?.initialChunks
      let page: number = startPage
      let prefetched: string[] | null = null
      let prefetchedPage: number | null = null

      if (startPage + 1 <= lastPage) {
        void prefetchPageChunks(
          documentIdRef.current,
          startPage + 1,
          engine,
          (p) => getPageParagraphsRef.current(p),
        )
      }

      while (isProducerActive(producerGeneration) && page <= lastPage) {
        let chunks: string[]
        if (page === startPage && initialChunks !== undefined) {
          chunks = initialChunks
          if (chunks.length === 0) {
            const advanced = await advancePastEmptyPages(
              page + 1,
              lastPage,
              producerGeneration,
              engine,
            )
            if (!advanced) {
              break
            }
            page = advanced.page
            chunks = advanced.chunks
            prefetched = null
            prefetchedPage = null
          }
        } else if (prefetchedPage === page && prefetched !== null) {
          chunks = prefetched
        } else {
          const advanced = await advancePastEmptyPages(
            page,
            lastPage,
            producerGeneration,
            engine,
          )
          if (!advanced) {
            break
          }
          page = advanced.page
          chunks = advanced.chunks
          prefetched = null
          prefetchedPage = null
        }

        if (!isProducerActive(producerGeneration)) {
          return
        }

        const nextPage: number = page + 1
        const nextPrefetchPromise =
          nextPage <= lastPage
            ? prefetchPageChunks(
                documentIdRef.current,
                nextPage,
                engine,
                (p) => getPageParagraphsRef.current(p),
              )
            : null

        for (let i = 0; i < chunks.length; i += 1) {
          if (!isProducerActive(producerGeneration)) {
            return
          }

          await waitForCapacity(producerGeneration)
          if (!isProducerActive(producerGeneration)) {
            return
          }

          const socket = socketRef.current
          if (!socket) {
            return
          }

          const meta: ChunkMeta = {
            page,
            isLastOfPage: i === chunks.length - 1,
            producerGeneration,
          }
          pendingChunkMetaRef.current.push(meta)
          socket.enqueueOne(chunks[i]!)
          sentChunksRef.current += 1
          notifyCapacityWaiters()
        }

        if (nextPrefetchPromise) {
          prefetched = await nextPrefetchPromise
          prefetchedPage = nextPage
        } else {
          prefetched = null
          prefetchedPage = null
        }

        if (!isProducerActive(producerGeneration)) {
          return
        }

        page += 1
      }

      if (producerGeneration === producerGenerationRef.current) {
        producerDoneRef.current = true
        void maybeStopWhenDrained()
      }
    },
    [
      advancePastEmptyPages,
      isProducerActive,
      maybeStopWhenDrained,
      notifyCapacityWaiters,
      pdfRef,
      stopReading,
      waitForCapacity,
    ],
  )

  const onUserNavigatePage = useCallback(
    async (page: number) => {
      if (!isReadingRef.current) {
        return
      }

      const socket = socketRef.current
      const pdf = pdfRef.current
      if (!socket || !pdf) {
        return
      }

      producerGenerationRef.current += 1
      const producerGeneration = producerGenerationRef.current
      const { engine } = selectedVoiceRef.current

      invalidateChunkCache()
      const chunksPromise = advancePastEmptyPages(
        page,
        pdf.numPages,
        producerGeneration,
        engine,
      )

      audioGenerationRef.current += 1
      clearLocalAudio(false)
      producerDoneRef.current = false

      const [generation, advanced] = await Promise.all([
        socket.clearAndWait(),
        chunksPromise,
      ])

      if (
        !isReadingRef.current ||
        producerGeneration !== producerGenerationRef.current
      ) {
        return
      }

      readGenerationRef.current = generation
      expectedAudioIndexRef.current = 0
      completedAudioRef.current = 0
      serverTextDepthRef.current = 0
      serverSynthInFlightRef.current = 0
      serverPendingSendRef.current = 0

      if (!advanced) {
        producerDoneRef.current = true
        void maybeStopWhenDrained()
        return
      }

      const { page: resolvedPage, chunks } = advanced
      onReadingPageChangeRef.current(resolvedPage)

      for (let i = 0; i < chunks.length; i += 1) {
        if (producerGeneration !== producerGenerationRef.current) {
          return
        }
        pendingChunkMetaRef.current.push({
          page: resolvedPage,
          isLastOfPage: i === chunks.length - 1,
          producerGeneration,
        })
        socket.enqueueOne(chunks[i]!)
        sentChunksRef.current += 1
      }
      notifyCapacityWaiters()

      if (resolvedPage < pdf.numPages) {
        void runContinuousReading(resolvedPage + 1, producerGeneration)
      } else if (producerGeneration === producerGenerationRef.current) {
        producerDoneRef.current = true
        void maybeStopWhenDrained()
      }
    },
    [
      advancePastEmptyPages,
      clearLocalAudio,
      maybeStopWhenDrained,
      notifyCapacityWaiters,
      runContinuousReading,
    ],
  )

  const toggleReadAloud = useCallback(
    async (startPage: number) => {
      if (isTransitioningRef.current) {
        return
      }

      if (isReadingRef.current) {
        isTransitioningRef.current = true
        try {
          await stopReading()
        } finally {
          isTransitioningRef.current = false
        }
        return
      }

      isTransitioningRef.current = true
      setIsStarting(true)
      setError(null)
      invalidateChunkCache()

      try {
        const { engine, id: voiceId } = selectedVoiceRef.current
        const socket = new TtsReadSocket()

        socket.onPipelineDepth((depth) => {
          serverTextDepthRef.current = depth.textDepth
          serverSynthInFlightRef.current = depth.synthInFlight
          serverPendingSendRef.current = depth.pendingSend
          notifyCapacityWaiters()
        })

        socket.onAudio(async (index, wavBytes, readGeneration) => {
          if (!isReadingRef.current) {
            return
          }

          if (index < expectedAudioIndexRef.current) {
            return
          }

          const generationOk =
            readGeneration === undefined ||
            readGeneration === readGenerationRef.current
          if (!generationOk || index !== expectedAudioIndexRef.current) {
            return
          }

          if (sentChunksRef.current === 0) {
            return
          }

          const generation = audioGenerationRef.current

          try {
            const ctx = await ensureAudioContext()
            const buffer = await ctx.decodeAudioData(wavBytes.slice(0))
            if (
              !isReadingRef.current ||
              generation !== audioGenerationRef.current ||
              (readGeneration !== undefined &&
                readGeneration !== readGenerationRef.current) ||
              index !== expectedAudioIndexRef.current
            ) {
              return
            }

            let meta = pendingChunkMetaRef.current.shift()
            while (
              meta &&
              meta.producerGeneration !== producerGenerationRef.current
            ) {
              meta = pendingChunkMetaRef.current.shift()
            }
            if (!meta) {
              if (sentChunksRef.current > 0) {
                expectedAudioIndexRef.current += 1
              }
              return
            }

            expectedAudioIndexRef.current += 1
            enqueueBuffer(buffer, meta, generation)
          } catch (err) {
            console.error(err)
          }
        })

        socket.onEvent((event) => {
          if (event.event === 'cleared') {
            expectedAudioIndexRef.current = 0
            readGenerationRef.current = event.generation
            sentChunksRef.current = 0
            completedAudioRef.current = 0
            serverTextDepthRef.current = 0
            serverSynthInFlightRef.current = 0
            serverPendingSendRef.current = 0
            notifyCapacityWaiters()
          }
          if (event.event === 'audio_end' && isReadingRef.current) {
            const gen = event.generation
            if (gen === undefined || gen === readGenerationRef.current) {
              completedAudioRef.current += 1
              notifyCapacityWaiters()
              void maybeStopWhenDrained()
            }
          }
          if (event.event === 'error' && isReadingRef.current) {
            setError(event.message)
          }
        })

        await socket.connect()
        socketRef.current = socket
        // Backend synth at 1×; client playbackRate controls audible speed for all engines.
        await socket.start(engine, voiceId, 1)
        await ensureAudioContext()

        isReadingRef.current = true
        setIsReading(true)
        producerDoneRef.current = false
        producerGenerationRef.current += 1
        const producerGeneration = producerGenerationRef.current

        void runContinuousReading(startPage, producerGeneration)
      } catch (err) {
        console.error(err)
        setError('No se pudo iniciar la lectura en voz alta.')
        await stopReading()
      } finally {
        isTransitioningRef.current = false
        setIsStarting(false)
      }
    },
    [
      enqueueBuffer,
      ensureAudioContext,
      maybeStopWhenDrained,
      notifyCapacityWaiters,
      runContinuousReading,
      stopReading,
    ],
  )

  useEffect(() => {
    return () => {
      void stopReading()
    }
  }, [stopReading])

  return {
    isReading,
    isStarting,
    error,
    toggleReadAloud,
    stopReading,
    onUserNavigatePage,
    clearError: () => setError(null),
  }
}
