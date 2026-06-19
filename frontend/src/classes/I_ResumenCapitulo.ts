export interface I_ResumenCapitulo {
  numero: number
  nombre: string
  resumen: string
}

export type ResumenesStatus = 'idle' | 'loading' | 'ready' | 'failed'

export interface I_ResumenesMetadata {
  items: I_ResumenCapitulo[]
  status: ResumenesStatus
  error: string | null
  total: number
  percent: number
}
