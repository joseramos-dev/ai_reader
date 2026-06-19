import { useEffect, useRef, useState } from 'react'

import type { I_SelectedVoice, I_TtsVoice } from '../../classes/I_TtsVoice.ts'
import {
  ENGINE_LABELS,
  SUPPORTED_ENGINES,
  filterSupportedVoices,
} from '../../classes/I_TtsVoice.ts'
import { VoiceLanguageFlag } from './VoiceLanguageFlag.tsx'

interface VoiceSelectorProps {
  voices: I_TtsVoice[]
  selectedVoice: I_SelectedVoice
  disabled?: boolean
  onVoiceChange: (voice: I_SelectedVoice) => void
}

export function VoiceSelector({
  voices,
  selectedVoice,
  disabled = false,
  onVoiceChange,
}: VoiceSelectorProps) {
  const [isOpen, setIsOpen] = useState(false)
  const rootRef = useRef<HTMLDivElement>(null)

  const availableVoices = filterSupportedVoices(voices)

  useEffect(() => {
    if (!isOpen) {
      return
    }

    function handlePointerDown(event: MouseEvent) {
      if (!rootRef.current?.contains(event.target as Node)) {
        setIsOpen(false)
      }
    }

    document.addEventListener('mousedown', handlePointerDown)
    return () => document.removeEventListener('mousedown', handlePointerDown)
  }, [isOpen])

  const selected = availableVoices.find(
    (voice) => voice.id === selectedVoice.id && voice.engine === selectedVoice.engine,
  )

  const grouped = SUPPORTED_ENGINES.map((engine) => ({
    engine,
    label: ENGINE_LABELS[engine] ?? engine,
    voices: availableVoices.filter((v) => v.engine === engine),
  })).filter((group) => group.voices.length > 0)

  return (
    <div ref={rootRef} className="relative">
      <button
        type="button"
        disabled={disabled || availableVoices.length === 0}
        onClick={() => setIsOpen((open) => !open)}
        className="rounded-md border border-zinc-300 px-2.5 py-1.5 text-sm font-medium text-zinc-700 transition-colors hover:bg-zinc-100 disabled:opacity-40 dark:border-zinc-600 dark:text-zinc-200 dark:hover:bg-zinc-800"
        aria-expanded={isOpen}
        aria-haspopup="listbox"
      >
        <span className="inline-flex items-center gap-1.5">
          <span>Voz: {selected?.label ?? selectedVoice.id}</span>
          {selected && (
            <VoiceLanguageFlag languageHint={selected.language_hint} />
          )}
        </span>
      </button>

      {isOpen && (
        <ul
          role="listbox"
          className="absolute bottom-full left-0 z-50 mb-1 max-h-48 min-w-56 overflow-y-auto rounded-lg border border-zinc-200 bg-white py-1 shadow-lg dark:border-zinc-700 dark:bg-zinc-900"
        >
          {grouped.map((group) => (
            <li key={group.engine}>
              <div className="px-3 py-1.5 text-xs font-semibold uppercase tracking-wide text-zinc-400">
                {group.label}
              </div>
              <ul>
                {group.voices.map((voice) => {
                  const isSelected =
                    voice.id === selectedVoice.id && voice.engine === selectedVoice.engine
                  return (
                    <li key={`${voice.engine}-${voice.id}`} role="option" aria-selected={isSelected}>
                      <button
                        type="button"
                        onClick={() => {
                          onVoiceChange({ engine: voice.engine, id: voice.id })
                          setIsOpen(false)
                        }}
                        className={
                          isSelected
                            ? 'flex w-full items-center gap-2 px-3 py-2 text-left text-sm bg-violet-50 text-violet-700 dark:bg-violet-950 dark:text-violet-200'
                            : 'flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-zinc-700 hover:bg-zinc-100 dark:text-zinc-200 dark:hover:bg-zinc-800'
                        }
                      >
                        <span className="min-w-0 flex-1 truncate">{voice.label}</span>
                        <VoiceLanguageFlag languageHint={voice.language_hint} />
                      </button>
                    </li>
                  )
                })}
              </ul>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
