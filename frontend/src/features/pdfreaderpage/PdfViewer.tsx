import { useCallback, useEffect, useRef, useState } from 'react'

import { Document, Page, pdfjs } from 'react-pdf'

import type { PDFDocumentProxy } from 'pdfjs-dist'

import 'react-pdf/dist/Page/AnnotationLayer.css'

import 'react-pdf/dist/Page/TextLayer.css'



import { updatePageActual } from '../../api/documentsApi.ts'

import { listVoices, loadEngine } from '../../api/ttsApi.ts'

import type { I_CapitulosMetadata } from '../../classes/I_Capitulo.ts'

import type { I_ResumenesMetadata } from '../../classes/I_ResumenCapitulo.ts'

import type { I_SelectedVoice, I_TtsVoice } from '../../classes/I_TtsVoice.ts'

import { filterSupportedVoices } from '../../classes/I_TtsVoice.ts'

import { useChapterSummaries } from '../../public/hooks/useChapterSummaries.ts'

import { useChapters } from '../../public/hooks/useChapters.ts'

import { usePageTextProcessing } from '../../public/hooks/usePageTextProcessing.ts'

import { usePageTextWindow } from '../../public/hooks/usePageTextWindow.ts'

import { useReadAloud } from '../../public/hooks/useReadAloud.ts'

import { useTtsModelReady } from '../../public/hooks/useTtsModelReady.ts'

import { ChapterMenu } from './ChapterMenu.tsx'

import { PageTextOverlay } from './PageTextOverlay.tsx'

import { PageTextProcessingBanner } from './PageTextProcessingBanner.tsx'

import { PdfBottomBar } from './PdfBottomBar.tsx'

import { PdfReaderToolbar } from './PdfReaderToolbar.tsx'

import { SummaryPanel } from './SummaryPanel.tsx'



pdfjs.GlobalWorkerOptions.workerSrc = new URL(

  'pdfjs-dist/build/pdf.worker.min.mjs',

  import.meta.url,

).toString()



const PATCH_DEBOUNCE_MS = 300

const ZOOM_STEP = 0.1

const ZOOM_MIN = 0.5

const ZOOM_MAX = 3

const WHEEL_ZOOM_COOLDOWN_MS = 80

const DEFAULT_SPEECH_SPEED = 1



function clampZoom(scale: number): number {

  return Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, Math.round(scale * 100) / 100))

}



interface PdfViewerProps {

  documentId: string

  fileName: string

  fileUrl: string

  initialPage: number

  initialCapitulos?: I_CapitulosMetadata

  initialResumenes?: I_ResumenesMetadata

  onChangePdf: () => void

}



export function PdfViewer({

  documentId,

  fileName,

  fileUrl,

  initialPage,

  initialCapitulos,

  initialResumenes,

  onChangePdf,

}: PdfViewerProps) {

  const [numPages, setNumPages] = useState(0)

  const [pageNumber, setPageNumber] = useState(initialPage)

  const [scale, setScale] = useState(1)

  const [speechSpeed, setSpeechSpeed] = useState(DEFAULT_SPEECH_SPEED)

  const scrollRef = useRef<HTMLDivElement>(null)

  const lastWheelZoomAtRef = useRef(0)

  const pdfRef = useRef<PDFDocumentProxy | null>(null)

  const [voices, setVoices] = useState<I_TtsVoice[]>([])

  const [selectedVoice, setSelectedVoice] = useState<I_SelectedVoice>({

    engine: 'pocket_tts',

    id: 'lola',

  })

  const [isEngineLoading, setIsEngineLoading] = useState(false)

  const [engineError, setEngineError] = useState<string | null>(null)

  const [isTextOverlayOpen, setIsTextOverlayOpen] = useState(false)



  const { isModelLoading: isDefaultEngineLoading } = useTtsModelReady()



  const {

    getParagraphsForReading,

    isCurrentLoading: isPageTextLoading,

    currentParagraphs,

    isPageTextReady,

  } = usePageTextWindow({

    documentId,

    pageNumber,

    numPages,

  })



  const { status: pageTextProcessing, isProcessing: isBulkTextProcessing } =
    usePageTextProcessing({ documentId })



  const handleReadingPageChange = useCallback((page: number) => {

    setPageNumber(page)

  }, [])



  const {

    isReading,

    isStarting,

    error,

    toggleReadAloud,

    stopReading,

    onUserNavigatePage,

    clearError,

  } = useReadAloud({

    pdfRef,

    documentId,

    numPages,

    selectedVoice,

    speechSpeed,

    getPageParagraphs: getParagraphsForReading,

    onReadingPageChange: handleReadingPageChange,

  })



  const {

    chapters,

    isLoading: chaptersLoading,

    isMenuOpen: isChapterMenuOpen,

    openMenu: openChapterMenu,

    closeMenu: closeChapterMenu,

    onPdfLoaded,

    chaptersButtonDisabled,

    chaptersReady,

  } = useChapters({

    documentId,

    pdfRef,

    initialCapitulos,

    textProcessingComplete: pageTextProcessing?.is_complete ?? false,

  })



  const {

    resumenes,

    isLoading: summariesLoading,

    isPanelOpen: isSummaryPanelOpen,

    togglePanel: toggleSummaryPanel,

    closePanel: closeSummaryPanel,

    retryGeneration,

    summaryProgressPercent,

    summaryButtonDisabled,

  } = useChapterSummaries({

    documentId,

    initialResumenes,

    chaptersReady,

    textProcessingComplete: pageTextProcessing?.is_complete ?? false,

  })



  const isReadLoading =
    isDefaultEngineLoading || isEngineLoading || isStarting || isPageTextLoading



  useEffect(() => {

    listVoices()

      .then(async (loaded) => {

        const supported = filterSupportedVoices(loaded)

        setVoices(supported)

        const defaultVoice = supported.find((voice) => voice.is_default) ?? supported[0]

        if (defaultVoice) {

          setSelectedVoice({ engine: defaultVoice.engine, id: defaultVoice.id })

          try {

            await loadEngine(defaultVoice.engine)

          } catch {

            // Motor por defecto no pudo cargarse; handleVoiceChange lo reintentará.

          }

        }

      })

      .catch(() => {})

  }, [])



  const handleVoiceChange = useCallback(

    async (voice: I_SelectedVoice) => {

      setEngineError(null)

      const previousEngine = selectedVoice.engine



      setSelectedVoice(voice)



      if (voice.engine === previousEngine) {

        return

      }



      if (isReading) {

        await stopReading()

      }



      setIsEngineLoading(true)

      try {

        await loadEngine(voice.engine)

      } catch (err) {

        console.error(err)

        setEngineError(

          err instanceof Error ? err.message : 'No se pudo cargar el motor TTS.',

        )

        setSelectedVoice({ engine: previousEngine, id: selectedVoice.id })

      } finally {

        setIsEngineLoading(false)

      }

    },

    [selectedVoice.engine, selectedVoice.id, isReading, stopReading],

  )



  useEffect(() => {

    const handle = setTimeout(() => {

      updatePageActual(documentId, pageNumber).catch(() => {})

    }, PATCH_DEBOUNCE_MS)

    return () => clearTimeout(handle)

  }, [documentId, pageNumber])



  useEffect(() => {

    scrollRef.current?.scrollTo({ top: 0, left: 0 })

  }, [pageNumber])



  const navigateToPage = useCallback(

    (page: number) => {

      const clamped = Math.min(Math.max(1, page), numPages || page)

      if (isReading) {

        onUserNavigatePage(clamped)

      }

      setPageNumber(clamped)

    },

    [isReading, numPages, onUserNavigatePage],

  )



  function goToPrevPage() {

    navigateToPage(pageNumber - 1)

  }



  function goToNextPage() {

    navigateToPage(pageNumber + 1)

  }



  function zoomIn() {

    setScale((s) => clampZoom(s + ZOOM_STEP))

  }



  function zoomOut() {

    setScale((s) => clampZoom(s - ZOOM_STEP))

  }



  useEffect(() => {

    function handleKeyDown(event: KeyboardEvent) {

      const target = event.target as HTMLElement | null

      if (target && target.tagName === 'INPUT') {

        return

      }

      if (event.key === 'ArrowLeft') {

        goToPrevPage()

      } else if (event.key === 'ArrowRight') {

        goToNextPage()

      }

    }

    window.addEventListener('keydown', handleKeyDown)

    return () => window.removeEventListener('keydown', handleKeyDown)

  }, [numPages, pageNumber, isReading])



  useEffect(() => {

    const node = scrollRef.current

    if (!node) {

      return

    }

    function handleWheel(event: WheelEvent) {

      if (!event.ctrlKey) {

        return

      }

      event.preventDefault()

      const now = Date.now()

      if (now - lastWheelZoomAtRef.current < WHEEL_ZOOM_COOLDOWN_MS) {

        return

      }

      lastWheelZoomAtRef.current = now

      setScale((s) =>

        clampZoom(s + (event.deltaY < 0 ? ZOOM_STEP : -ZOOM_STEP)),

      )

    }

    node.addEventListener('wheel', handleWheel, { passive: false })

    return () => node.removeEventListener('wheel', handleWheel)

  }, [])



  const visiblePages = [pageNumber - 1, pageNumber, pageNumber + 1].filter(

    (p) => p >= 1 && p <= numPages,

  )



  const displayError = engineError ?? error



  return (

    <div className="flex flex-1 flex-col">

      <PdfReaderToolbar

        fileName={fileName}

        onChangePdf={onChangePdf}

        chaptersButtonDisabled={chaptersButtonDisabled}

        chaptersLoading={chaptersLoading}

        onToggleChapters={openChapterMenu}

        isPageTextReady={isPageTextReady}

        isTextOverlayOpen={isTextOverlayOpen}

        onToggleTextOverlay={() => setIsTextOverlayOpen((open) => !open)}

        summaryButtonDisabled={summaryButtonDisabled}

        summariesLoading={summariesLoading}

        summaryProgressPercent={summaryProgressPercent}

        isSummaryPanelOpen={isSummaryPanelOpen}

        onToggleSummary={toggleSummaryPanel}

      />

      <div className="flex flex-1 flex-col p-4">

      {displayError && (

        <div className="mx-auto mb-2 flex items-center gap-2 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-900 dark:border-amber-800 dark:bg-amber-950 dark:text-amber-100">

          <span>{displayError}</span>

          <button

            type="button"

            onClick={() => {

              clearError()

              setEngineError(null)

            }}

            className="rounded px-1.5 py-0.5 hover:bg-amber-100 dark:hover:bg-amber-900"

          >

            ✕

          </button>

        </div>

      )}

      {isBulkTextProcessing && pageTextProcessing && (
        <PageTextProcessingBanner
          ready={pageTextProcessing.ready}
          total={pageTextProcessing.total}
          percent={pageTextProcessing.percent}
          failed={pageTextProcessing.failed}
        />
      )}

      <div ref={scrollRef} className="flex-1 overflow-auto pb-24">

        <Document

          file={fileUrl}

          onLoadSuccess={(pdf) => {

            pdfRef.current = pdf

            setNumPages(pdf.numPages)

            setPageNumber((page) => Math.min(Math.max(1, page), pdf.numPages))

            onPdfLoaded()

          }}

          loading={<p className="p-8 text-sm text-zinc-500">Cargando PDF…</p>}

          error={

            <p className="p-8 text-sm text-red-600">No se pudo abrir el PDF.</p>

          }

        >

          <div className="flex flex-col items-center">

            {visiblePages.map((page) => (

              <div key={page} className={page === pageNumber ? '' : 'hidden'}>

                <div className="relative inline-block">

                  <Page

                    pageNumber={page}

                    scale={scale}

                    renderAnnotationLayer

                    renderTextLayer

                    className="shadow-lg"

                  />

                  {page === pageNumber && isTextOverlayOpen && (

                    <PageTextOverlay

                      paragraphs={currentParagraphs}

                      isLoading={isPageTextLoading}

                    />

                  )}

                </div>

              </div>

            ))}

          </div>

        </Document>

      </div>



      {(numPages > 0 || isReadLoading) && (

        <>

          <ChapterMenu

            chapters={chapters}

            isOpen={isChapterMenuOpen}

            onClose={closeChapterMenu}

            onSelectChapter={navigateToPage}

          />

          <SummaryPanel

            chapters={chapters}

            resumenes={resumenes}

            pageNumber={pageNumber}

            isOpen={isSummaryPanelOpen}

            onClose={closeSummaryPanel}

            onRetry={() => void retryGeneration()}

          />

          <PdfBottomBar

            pageNumber={pageNumber}

            numPages={numPages || 1}

            isReading={isReading}

            isModelLoading={isReadLoading}

            speechSpeed={speechSpeed}

            voices={voices}

            selectedVoice={selectedVoice}

            onVoiceChange={(voice) => void handleVoiceChange(voice)}

            onSpeechSpeedChange={setSpeechSpeed}

            onPrev={goToPrevPage}

            onNext={goToNextPage}

            onGoToPage={navigateToPage}

            onZoomIn={zoomIn}

            onZoomOut={zoomOut}

            onReadAloud={() => void toggleReadAloud(pageNumber)}

          />

        </>

      )}

      </div>

    </div>

  )

}

