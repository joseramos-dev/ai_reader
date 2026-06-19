import type { I_ApiResponse } from '../classes/I_ApiResponse'

/** Same-origin in dev (Vite proxies /api → backend). Override with VITE_API_BASE_URL in prod. */
export const API_BASE = resolveApiBase()

function resolveApiBase(): string {
  const fromEnv = import.meta.env.VITE_API_BASE_URL
  if (fromEnv) {
    return fromEnv.replace(/\/$/, '')
  }
  if (import.meta.env.DEV) {
    return ''
  }
  return 'http://localhost:8000'
}

export function getWebSocketOrigin(): string {
  if (API_BASE) {
    const url = new URL(API_BASE)
    url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
    return url.origin
  }
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}`
}

export async function apiFetch<T>(
  path: string,
  init?: RequestInit,
): Promise<I_ApiResponse<T>> {
  const headers = new Headers(init?.headers)
  const isFormData = init?.body instanceof FormData
  if (init?.body && !isFormData && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }

  const response = await fetch(`${API_BASE}${path}`, { ...init, headers })
  const data = (await response.json()) as T
  return { data, status: response.status }
}
