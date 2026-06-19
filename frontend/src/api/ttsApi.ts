import type { I_TtsVoice } from '../classes/I_TtsVoice.ts'
import { apiFetch } from './client.ts'

export interface I_EngineMemoryInfo {
  active_engine_loaded: boolean
  cuda_available: boolean
  text_queue_depth: number
  voice_cache_size: number
}

export interface I_TtsHealth {
  status: string
  active_engine: string | null
  loading: boolean
  engines: Record<string, { loaded: boolean }>
  memory: I_EngineMemoryInfo
}

export async function getTtsHealth(): Promise<I_TtsHealth> {
  const { data, status } = await apiFetch<I_TtsHealth>('/health')
  if (status >= 400) {
    throw new Error('No se pudo comprobar el estado del servicio TTS.')
  }
  return data
}

export async function listVoices(): Promise<I_TtsVoice[]> {
  const { data, status } = await apiFetch<I_TtsVoice[]>('/voices')
  if (status >= 400) {
    throw new Error('No se pudo cargar la lista de voces.')
  }
  return data
}

export async function loadEngine(engine: string): Promise<void> {
  const { status, data } = await apiFetch<{ detail?: string }>(
    '/api/v1/tts/engine/load',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ engine }),
    },
  )
  if (status >= 400) {
    const message =
      typeof data === 'object' && data && 'detail' in data
        ? String(data.detail)
        : 'No se pudo cargar el motor TTS.'
    throw new Error(message)
  }
}
