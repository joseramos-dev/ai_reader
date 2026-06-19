import { useRef, useState } from 'react'
import type { DragEvent } from 'react'

import { PdfValidator } from '../../classes/PdfValidator'

interface PdfDropzoneProps {
  onFileAccepted: (file: File) => void
  onError: (message: string) => void
  disabled?: boolean
}

export function PdfDropzone({
  onFileAccepted,
  onError,
  disabled = false,
}: PdfDropzoneProps) {
  const [isDragging, setIsDragging] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)

  function validateAndAccept(file: File) {
    if (!PdfValidator.isPdf(file)) {
      onError('El archivo debe ser un PDF.')
      return
    }
    onFileAccepted(file)
  }

  function handleDrop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault()
    setIsDragging(false)
    if (disabled) {
      return
    }
    const file = event.dataTransfer.files[0]
    if (file) {
      validateAndAccept(file)
    }
  }

  function handleDragOver(event: DragEvent<HTMLDivElement>) {
    event.preventDefault()
    if (!disabled) {
      setIsDragging(true)
    }
  }

  return (
    <div
      onDrop={handleDrop}
      onDragOver={handleDragOver}
      onDragLeave={() => setIsDragging(false)}
      onClick={() => !disabled && inputRef.current?.click()}
      className={`flex flex-col items-center justify-center gap-3 rounded-xl border-2 border-dashed p-12 text-center transition-colors ${
        disabled ? 'cursor-not-allowed opacity-60' : 'cursor-pointer'
      } ${
        isDragging
          ? 'border-violet-500 bg-violet-50 dark:bg-violet-950/30'
          : 'border-zinc-300 hover:border-violet-400 dark:border-zinc-700'
      }`}
    >
      <input
        ref={inputRef}
        type="file"
        accept=".pdf,application/pdf"
        className="hidden"
        disabled={disabled}
        onChange={(event) => {
          const file = event.target.files?.[0]
          if (file) {
            validateAndAccept(file)
          }
          event.target.value = ''
        }}
      />
      <p className="text-base font-medium text-zinc-700 dark:text-zinc-200">
        Arrastra un PDF aquí o haz clic para seleccionar
      </p>
    </div>
  )
}
