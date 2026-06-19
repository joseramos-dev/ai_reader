export interface I_TtsVoice {
  id: string
  engine: string
  language_hint: string
  label: string
  is_default: boolean
}

export interface I_SelectedVoice {
  engine: string
  id: string
}

export const ENGINE_LABELS: Record<string, string> = {
  pocket_tts: 'Pocket-TTS',
  kokoro: 'Kokoro',
  piper: 'Piper',
}

export const SUPPORTED_ENGINES = ['pocket_tts', 'kokoro', 'piper'] as const

export type SupportedEngine = (typeof SUPPORTED_ENGINES)[number]

export function isSupportedEngine(engine: string): engine is SupportedEngine {
  return (SUPPORTED_ENGINES as readonly string[]).includes(engine)
}

export function filterSupportedVoices(voices: I_TtsVoice[]): I_TtsVoice[] {
  return voices.filter((voice) => isSupportedEngine(voice.engine))
}
