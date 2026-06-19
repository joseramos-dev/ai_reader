import { useEffect, useRef, useState } from 'react'



import type { I_SelectedVoice, I_TtsVoice } from '../../classes/I_TtsVoice.ts'

import { VoiceSelector } from './VoiceSelector.tsx'



interface PdfBottomBarProps {

  pageNumber: number

  numPages: number

  isReading?: boolean

  isModelLoading?: boolean

  speechSpeed: number

  voices: I_TtsVoice[]

  selectedVoice: I_SelectedVoice

  onVoiceChange: (voice: I_SelectedVoice) => void

  onSpeechSpeedChange: (speed: number) => void

  onPrev: () => void

  onNext: () => void

  onGoToPage: (page: number) => void

  onZoomIn: () => void

  onZoomOut: () => void

  onReadAloud: () => void

}



const btn =

  'rounded-md px-3 py-1.5 text-sm font-medium text-violet-600 transition-colors hover:bg-zinc-100 disabled:opacity-40 disabled:hover:bg-transparent dark:hover:bg-zinc-800'



export function PdfBottomBar({

  pageNumber,

  numPages,

  isReading = false,

  isModelLoading = false,

  speechSpeed,

  voices,

  selectedVoice,

  onVoiceChange,

  onSpeechSpeedChange,

  onPrev,

  onNext,

  onGoToPage,

  onZoomIn,

  onZoomOut,

  onReadAloud,

}: PdfBottomBarProps) {

  const [isEditing, setIsEditing] = useState(false)

  const [value, setValue] = useState('')

  const inputRef = useRef<HTMLInputElement>(null)



  useEffect(() => {

    if (isEditing) {

      inputRef.current?.focus()

      inputRef.current?.select()

    }

  }, [isEditing])



  function startEditing() {

    setValue(String(pageNumber))

    setIsEditing(true)

  }



  function commit() {

    const parsed = Number(value)

    if (Number.isInteger(parsed) && parsed >= 1 && parsed <= numPages) {

      onGoToPage(parsed)

    }

    setIsEditing(false)

  }



  return (

    <div className="fixed inset-x-0 bottom-0 z-50 flex justify-center p-4">

      <div className="flex flex-wrap items-center justify-center gap-2 rounded-xl border border-zinc-200 bg-white px-3 py-2 shadow-lg dark:border-zinc-700 dark:bg-zinc-900">

        <button type="button" onClick={onZoomOut} className={btn} aria-label="Alejar">

          −

        </button>

        <button

          type="button"

          onClick={onPrev}

          disabled={pageNumber <= 1}

          className={btn}

        >

          Anterior

        </button>



        {isEditing ? (

          <input

            ref={inputRef}

            type="number"

            min={1}

            max={numPages}

            value={value}

            onChange={(e) => setValue(e.target.value)}

            onBlur={() => setIsEditing(false)}

            onKeyDown={(e) => {

              if (e.key === 'Enter') {

                commit()

              } else if (e.key === 'Escape') {

                setIsEditing(false)

              }

            }}

            className="w-16 rounded-md border border-zinc-300 bg-transparent px-2 py-1 text-center text-sm text-zinc-800 dark:border-zinc-600 dark:text-zinc-100"

          />

        ) : (

          <button

            type="button"

            onClick={startEditing}

            className="min-w-16 rounded-md px-2 py-1 text-sm tabular-nums text-zinc-700 hover:bg-zinc-100 dark:text-zinc-200 dark:hover:bg-zinc-800"

          >

            {pageNumber}/{numPages}

          </button>

        )}



        <button

          type="button"

          onClick={onNext}

          disabled={pageNumber >= numPages}

          className={btn}

        >

          Siguiente

        </button>

        <button type="button" onClick={onZoomIn} className={btn} aria-label="Acercar">

          +

        </button>



        <label className="flex items-center gap-1.5 text-xs text-zinc-600 dark:text-zinc-300">

          <span>Vel.</span>

          <input

            type="range"

            min={0.75}

            max={1.5}

            step={0.05}

            value={speechSpeed}

            disabled={isModelLoading}

            onChange={(e) => onSpeechSpeedChange(Number(e.target.value))}

            className="w-20 accent-violet-600 disabled:opacity-40"

            aria-label="Velocidad de lectura"

          />

          <span className="w-8 tabular-nums">{speechSpeed.toFixed(2)}×</span>

        </label>



        <VoiceSelector

          voices={voices}

          selectedVoice={selectedVoice}

          disabled={isReading || isModelLoading}

          onVoiceChange={onVoiceChange}

        />

        <button

          type="button"

          onClick={onReadAloud}

          disabled={isModelLoading}

          className={

            isModelLoading

              ? 'ml-1 rounded-md bg-zinc-400 px-3 py-1.5 text-sm font-medium text-white cursor-not-allowed'

              : isReading

                ? 'ml-1 rounded-md bg-red-600 px-3 py-1.5 text-sm font-medium text-white transition-colors hover:bg-red-700'

                : 'ml-1 rounded-md bg-violet-600 px-3 py-1.5 text-sm font-medium text-white transition-colors hover:bg-violet-700'

          }

        >

          {isModelLoading ? 'Cargando' : isReading ? 'Detener' : 'Leer'}

        </button>

      </div>

    </div>

  )

}

