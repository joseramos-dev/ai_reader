export interface I_Capitulo {
  numero: number
  nombre: string
  pagina: number
  longitud: number
}

export type CapitulosStatus = 'idle' | 'loading' | 'ready' | 'failed'
export type CapitulosSource = 'outline' | 'llm' | 'text_scan' | 'text_scan_llm' | null

export interface I_CapitulosMetadata {
  items: I_Capitulo[]
  status: CapitulosStatus
  source: CapitulosSource
  error: string | null
}

export interface I_CapituloInput {
  nombre: string
  pagina: number
}
