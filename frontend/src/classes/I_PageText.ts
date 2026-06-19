export type PageTextStatus = 'idle' | 'loading' | 'ready' | 'failed'



export interface I_PageTextMetadata {

  page: number

  paragraphs: string[]

  status: PageTextStatus

  error: string | null

}



export interface I_PageTextProcessingStatus {

  total: number

  ready: number

  loading: number

  failed: number

  idle: number

  percent: number

  is_complete: boolean

}

