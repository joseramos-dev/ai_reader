import { getWebSocketOrigin } from './client.ts'

export type TtsReadEvent =
  | { event: 'ready' }
  | { event: 'audio_start'; index: number; generation?: number }
  | { event: 'audio_end'; index: number; generation?: number }
  | { event: 'cleared'; generation: number }
  | { event: 'queue_empty' }
  | {
      event: 'pipeline_depth'
      text_depth: number
      synth_in_flight: number
      pending_send: number
    }
  | { event: 'speed_updated'; speed: number }
  | { event: 'error'; message: string }

export interface PipelineDepthSnapshot {
  textDepth: number
  synthInFlight: number
  pendingSend: number
}

function wsBaseUrl(): string {
  return getWebSocketOrigin()
}

export class TtsReadSocket {
  private ws: WebSocket | null = null
  private pendingAudioIndex: number | null = null
  private pendingAudioGeneration: number | undefined = undefined
  private onAudioCb:
    | ((index: number, data: ArrayBuffer, generation: number | undefined) => void)
    | null = null
  private onEventCb: ((event: TtsReadEvent) => void) | null = null
  private onDepthCb: ((depth: PipelineDepthSnapshot) => void) | null = null
  private readyResolve: (() => void) | null = null
  private readyReject: ((error: Error) => void) | null = null
  private clearResolvers: Array<(generation: number) => void> = []

  connect(): Promise<void> {
    return new Promise((resolve, reject) => {
      this.ws = new WebSocket(`${wsBaseUrl()}/api/v1/tts/read`)
      this.ws.binaryType = 'arraybuffer'

      this.ws.onopen = () => resolve()
      this.ws.onerror = () => reject(new Error('No se pudo conectar al servicio TTS'))
      this.ws.onmessage = (event) => this.handleMessage(event)
    })
  }

  onAudio(
    callback: (index: number, data: ArrayBuffer, generation: number | undefined) => void,
  ): void {
    this.onAudioCb = callback
  }

  onEvent(callback: (event: TtsReadEvent) => void): void {
    this.onEventCb = callback
  }

  onPipelineDepth(callback: (depth: PipelineDepthSnapshot) => void): void {
    this.onDepthCb = callback
  }

  start(engine: string, voice: string, speed = 1): Promise<void> {
    return new Promise((resolve, reject) => {
      this.readyResolve = resolve
      this.readyReject = reject
      this.send({ action: 'start', engine, voice, speed })
    })
  }

  setSpeed(speed: number): void {
    this.send({ action: 'set_speed', speed })
  }

  enqueue(phrases: string[]): void {
    if (phrases.length === 0) {
      return
    }
    this.send({ action: 'enqueue', phrases })
  }

  enqueueOne(phrase: string): void {
    this.enqueue([phrase])
  }

  clear(): void {
    this.send({ action: 'clear' })
  }

  clearAndWait(): Promise<number> {
    return new Promise((resolve) => {
      this.clearResolvers.push(resolve)
      this.send({ action: 'clear' })
    })
  }

  stop(): Promise<void> {
    const ws = this.ws
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      this.dispose()
      return Promise.resolve()
    }

    return new Promise((resolve) => {
      const finish = () => {
        window.clearTimeout(timer)
        this.dispose()
        resolve()
      }
      const timer = window.setTimeout(finish, 3000)
      ws.addEventListener('close', finish, { once: true })
      this.send({ action: 'stop' })
    })
  }

  dispose(): void {
    this.pendingAudioIndex = null
    this.pendingAudioGeneration = undefined
    this.onAudioCb = null
    this.onEventCb = null
    this.onDepthCb = null
    this.readyResolve = null
    this.readyReject = null
    this.clearResolvers = []

    if (this.ws) {
      this.ws.onopen = null
      this.ws.onclose = null
      this.ws.onerror = null
      this.ws.onmessage = null
      if (
        this.ws.readyState === WebSocket.OPEN ||
        this.ws.readyState === WebSocket.CONNECTING
      ) {
        this.ws.close()
      }
      this.ws = null
    }
  }

  private send(payload: Record<string, unknown>): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(payload))
    }
  }

  private handleMessage(event: MessageEvent): void {
    if (typeof event.data === 'string') {
      const msg = JSON.parse(event.data) as TtsReadEvent
      this.onEventCb?.(msg)

      if (msg.event === 'pipeline_depth') {
        this.onDepthCb?.({
          textDepth: msg.text_depth,
          synthInFlight: msg.synth_in_flight,
          pendingSend: msg.pending_send,
        })
      }

      if (msg.event === 'cleared') {
        this.pendingAudioIndex = null
        this.pendingAudioGeneration = undefined
        const waiters = this.clearResolvers.splice(0)
        for (const resolve of waiters) {
          resolve(msg.generation)
        }
      }

      if (msg.event === 'ready') {
        this.readyResolve?.()
        this.readyResolve = null
        this.readyReject = null
        return
      }

      if (msg.event === 'error') {
        this.readyReject?.(new Error(msg.message))
        this.readyResolve = null
        this.readyReject = null
        return
      }

      if (msg.event === 'audio_start') {
        this.pendingAudioIndex = msg.index
        this.pendingAudioGeneration =
          typeof msg.generation === 'number' ? msg.generation : undefined
      } else if (msg.event === 'audio_end') {
        this.pendingAudioIndex = null
        this.pendingAudioGeneration = undefined
      }
      return
    }

    if (event.data instanceof ArrayBuffer && this.pendingAudioIndex !== null) {
      const index = this.pendingAudioIndex
      const generation = this.pendingAudioGeneration
      this.onAudioCb?.(index, event.data, generation)
    }
  }
}
